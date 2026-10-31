package com.htb.carplan.shared.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Record
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * 13·14장 기록 칸. The car creates records when it parks; phones add photos and a note.
 * Non-trip days keep their records under an automatic "일상 기록" event in the solo group (Q37).
 */
class RecordRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) {
    fun observeRecords(groupId: String, eventId: String, date: String): Flow<List<Record>> = callbackFlow {
        val reg = db.collection(FirestorePaths.records(groupId, eventId, date)).orderBy("arrivedAt")
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(Record::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    /** Creates the record and returns its id. [arrivedAt] defaults to now. */
    suspend fun addRecord(groupId: String, eventId: String, date: String, record: Record): String {
        val ref = db.collection(FirestorePaths.records(groupId, eventId, date)).document()
        ref.set(record.copy(arrivedAt = record.arrivedAt ?: Timestamp.now())).await()
        return ref.id
    }

    /** An unplanned record made within [withinMs] at most [meters] from (lat, lng) — the same parking, not a new one. */
    suspend fun recentUnplannedNear(groupId: String, eventId: String, date: String, lat: Double, lng: Double, meters: Double, withinMs: Long): Record? {
        if (lat == 0.0 && lng == 0.0) return null
        val now = System.currentTimeMillis()
        return db.collection(FirestorePaths.records(groupId, eventId, date)).get().await().toObjects(Record::class.java)
            .filter { it.unplanned && it.arrivedAt != null && now - it.arrivedAt!!.toDate().time <= withinMs }
            .firstOrNull { com.htb.carplan.shared.calendar.RouteMath.metersBetween(lat, lng, it.place.lat, it.place.lng) <= meters }
    }

    /** 기록 탭 일기장 카드: one read of a day's records (places, first photo, first note). */
    suspend fun daySummary(groupId: String, eventId: String, date: String): List<Record> =
        db.collection(FirestorePaths.records(groupId, eventId, date)).get().await().toObjects(Record::class.java)
            .sortedBy { it.arrivedAt?.toDate()?.time ?: 0L }

    /** The car pulled away (P → D): close the newest open record (13장 9). */
    suspend fun markLeft(groupId: String, eventId: String, date: String, recordId: String) {
        db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId").update("leftAt", Timestamp.now()).await()
    }

    suspend fun setNote(groupId: String, eventId: String, date: String, recordId: String, note: String) {
        db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId").update("note", note.trim()).await()
    }

    /** Q36: fix a wrong place name (keeps the record's own coordinates when the new place has none). */
    suspend fun setPlace(groupId: String, eventId: String, date: String, recordId: String, place: Place) {
        db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId").update("place", place).await()
    }

    /** Uploads one photo (already downscaled JPEG bytes) and appends its URL (Q40: caller enforces 10). */
    suspend fun addPhoto(groupId: String, eventId: String, date: String, recordId: String, jpeg: ByteArray): String {
        val name = "${System.currentTimeMillis()}.jpg"
        val ref = storage.reference.child(FirestorePaths.recordPhoto(groupId, eventId, date, recordId, name))
        // The storage rule (18장) takes images only: without the type the upload arrives as octet-stream and is refused.
        ref.putBytes(jpeg, com.google.firebase.storage.StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
        val url = ref.downloadUrl.await().toString()
        db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId").update("photoUrls", FieldValue.arrayUnion(url)).await()
        return url
    }

    suspend fun removePhoto(groupId: String, eventId: String, date: String, recordId: String, url: String) {
        db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId").update("photoUrls", FieldValue.arrayRemove(url)).await()
        runCatching { storage.getReferenceFromUrl(url).delete().await() }
    }

    /**
     * Q37: the day's "일상 기록" event in the solo group (created on first use, id log-{date}).
     * Returns its event id.
     */
    suspend fun ensureDailyLog(soloGroupId: String, date: String, byUid: String): String {
        val id = FirestorePaths.dailyLogEventId(date)
        db.document(FirestorePaths.event(soloGroupId, id)).set(
            Event(categoryId = BuiltInCategory.OTHER.id, title = "일상 기록", startDate = date, endDate = date, allDay = true, createdBy = byUid),
            SetOptions.merge(),
        ).await()
        return id
    }
}
