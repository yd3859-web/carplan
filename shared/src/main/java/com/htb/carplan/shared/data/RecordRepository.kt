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

    /** A1: one drive the car made (points flat lat,lng — see [com.htb.carplan.shared.calendar.TrackMath]). */
    suspend fun addTrack(groupId: String, eventId: String, date: String, track: com.htb.carplan.shared.model.DriveTrack) {
        db.collection(FirestorePaths.tracks(groupId, eventId, date)).document().set(track).await()
    }

    /** A1: the day's drives in time order, each as points. Empty when the car never recorded (or on failure). */
    suspend fun tracks(groupId: String, eventId: String, date: String): List<List<Pair<Double, Double>>> = runCatching {
        db.collection(FirestorePaths.tracks(groupId, eventId, date)).get().await()
            .toObjects(com.htb.carplan.shared.model.DriveTrack::class.java)
            .sortedBy { it.startedAt?.toDate()?.time ?: 0L }
            .map { com.htb.carplan.shared.calendar.TrackMath.unflatten(it.points) }
    }.getOrDefault(emptyList())

    /** Creates the record and returns its id. [arrivedAt] defaults to now. */
    suspend fun addRecord(groupId: String, eventId: String, date: String, record: Record): String {
        val ref = db.collection(FirestorePaths.records(groupId, eventId, date)).document()
        ref.set(record.copy(arrivedAt = record.arrivedAt ?: Timestamp.now())).await()
        // Q58 "가 봄": the group has been here (recommendations list it before unknown places).
        com.htb.carplan.shared.calendar.PlaceRank.keyOf(record.place)?.let { id ->
            // A3: where it is too, so the phone can offer it again before any Kakao search.
            val p = record.place
            val memo = mutableMapOf<String, Any>("name" to p.name, "visited" to true, "visits" to FieldValue.increment(1), "lastAt" to Timestamp.now())
            if (p.lat != 0.0 || p.lng != 0.0) { memo["lat"] = p.lat; memo["lng"] = p.lng }
            if (p.address.isNotBlank()) memo["address"] = p.address
            if (p.category.isNotBlank()) memo["category"] = p.category
            runCatching { db.document("${FirestorePaths.places(groupId)}/$id").set(memo, SetOptions.merge()).await() }
        }
        return ref.id
    }

    /** Q58 [또 갈래요] on a record — on the record (count) and in the group's place memo (ranking). */
    suspend fun setAgain(groupId: String, eventId: String, date: String, record: Record, uid: String, on: Boolean) {
        val change = if (on) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
        db.document("${FirestorePaths.records(groupId, eventId, date)}/${record.id}").update("againBy", change).await()
        com.htb.carplan.shared.calendar.PlaceRank.keyOf(record.place)?.let { id ->
            db.document("${FirestorePaths.places(groupId)}/$id").set(mapOf("name" to record.place.name, "againBy" to change, "visited" to true), SetOptions.merge()).await()
        }
    }

    /** Q58: every place memo of these groups, merged by Kakao id (one read per group, at most a few dozen docs). */
    suspend fun placeMemos(groupIds: List<String>): Map<String, com.htb.carplan.shared.model.PlaceMemo> {
        val all = groupIds.flatMap { gid -> runCatching { db.collection(FirestorePaths.places(gid)).get().await().toObjects(com.htb.carplan.shared.model.PlaceMemo::class.java) }.getOrDefault(emptyList()) }
        return all.groupBy { it.kakaoId }.mapValues { (id, list) ->
            val at = list.firstOrNull { it.lat != 0.0 || it.lng != 0.0 } ?: list.first()
            val last = list.maxByOrNull { it.lastAt?.toDate()?.time ?: 0L } ?: list.first()
            com.htb.carplan.shared.model.PlaceMemo(
                id, list.first().name, list.flatMap { it.againBy }.distinct(), list.any { it.visited },
                at.lat, at.lng, at.address, list.firstOrNull { it.category.isNotBlank() }?.category.orEmpty(),
                list.sumOf { it.visits }, last.lastAt, last.parking.ifBlank { list.firstOrNull { it.parking.isNotBlank() }?.parking.orEmpty() },
                last.stayMinutes,
            )
        }
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
        val ref = db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId")
        val now = Timestamp.now()
        ref.update("leftAt", now).await()
        // A3: how long the stop lasted goes to the place memo ("1시간 머묾" in the phone's place list).
        runCatching {
            val r = ref.get().await().toObject(Record::class.java) ?: return@runCatching
            val minutes = r.arrivedAt?.let { (now.toDate().time - it.toDate().time) / 60_000 } ?: return@runCatching
            com.htb.carplan.shared.calendar.PlaceRank.keyOf(r.place)?.let { id ->
                db.document("${FirestorePaths.places(groupId)}/$id").set(mapOf("stayMinutes" to minutes), SetOptions.merge()).await()
            }
        }
    }

    suspend fun setNote(groupId: String, eventId: String, date: String, recordId: String, note: String) {
        db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId").update("note", note.trim()).await()
    }

    /** Q36: fix a wrong place name (keeps the record's own coordinates when the new place has none). */
    /** ★3: the spot written on a phone ("B2 C-14") next to where the car parked. */
    suspend fun setParkingNote(groupId: String, eventId: String, date: String, recordId: String, note: String) {
        val ref = db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId")
        ref.update("parkingNote", note.trim()).await()
        // A3: the spot is remembered with the place ("주차 B2" next time someone picks it).
        if (note.isNotBlank()) runCatching {
            val r = ref.get().await().toObject(Record::class.java) ?: return@runCatching
            com.htb.carplan.shared.calendar.PlaceRank.keyOf(r.place)?.let { id ->
                db.document("${FirestorePaths.places(groupId)}/$id").set(mapOf("parking" to note.trim()), SetOptions.merge()).await()
            }
        }
    }

    /** 묶음 6: the 주유 done at this stop goes on its record too — unless someone already wrote a line there. */
    suspend fun setNoteIfBlank(groupId: String, eventId: String, date: String, recordId: String, note: String) {
        val ref = db.document("${FirestorePaths.records(groupId, eventId, date)}/$recordId")
        if (ref.get().await().getString("note").isNullOrBlank()) ref.update("note", note.trim()).await()
    }

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
