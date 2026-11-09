package com.htb.carplan.shared.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.htb.carplan.shared.model.Pickup
import com.htb.carplan.shared.model.PickupStatus
import com.htb.carplan.shared.model.Place
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** 11장 픽업: one document per member who wants to be picked up on that trip day. */
class PickupRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    fun observe(groupId: String, eventId: String, date: String): Flow<List<Pickup>> = callbackFlow {
        val reg = db.collection(FirestorePaths.pickups(groupId, eventId, date)).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObjects(Pickup::class.java).orEmpty().sortedBy { it.order })
        }
        awaitClose { reg.remove() }
    }

    /** The member's own pickup place (creates or moves it; a new one goes last). */
    suspend fun setMine(groupId: String, eventId: String, date: String, uid: String, name: String, place: Place) {
        val col = db.collection(FirestorePaths.pickups(groupId, eventId, date))
        val existing = col.document(uid).get().await().toObject(Pickup::class.java)
        val order = existing?.order ?: ((col.get().await().toObjects(Pickup::class.java).maxOfOrNull { it.order } ?: -1) + 1)
        col.document(uid).set(Pickup(place = place, order = order, status = PickupStatus.WAITING, name = name), SetOptions.merge()).await()
        // 정원 2026-11-09: the pickup is also a slot in the day (not a separate "먼저 태우러 가요" row on top).
        TripRepository(db).upsertPickupStop(groupId, eventId, date, uid, name, place, byUid = uid)
    }

    suspend fun remove(groupId: String, eventId: String, date: String, uid: String) {
        db.document("${FirestorePaths.pickups(groupId, eventId, date)}/$uid").delete().await()
        TripRepository(db).removePickupStop(groupId, eventId, date, uid, byUid = uid)
    }

    /** "탔어요" (member) or the 10-minute timeout (car). */
    suspend fun setStatus(groupId: String, eventId: String, date: String, uid: String, status: PickupStatus) {
        db.document("${FirestorePaths.pickups(groupId, eventId, date)}/$uid").update("status", status.name).await()
    }

    /** The car reached the pickup place. */
    suspend fun markArrived(groupId: String, eventId: String, date: String, uid: String) {
        db.document("${FirestorePaths.pickups(groupId, eventId, date)}/$uid").update("arrivedAt", Timestamp.now()).await()
    }

    /** The car's driving time to this pickup (seconds), refreshed every ~30 s while sharing; Functions push "곧 도착" under 3 minutes. */
    suspend fun setEta(groupId: String, eventId: String, date: String, uid: String, seconds: Long?) {
        db.document("${FirestorePaths.pickups(groupId, eventId, date)}/$uid").update("etaSeconds", seconds).await()
    }

    suspend fun setOrder(groupId: String, eventId: String, date: String, orderedUids: List<String>) {
        db.runBatch { b -> orderedUids.forEachIndexed { i, uid -> b.update(db.document("${FirestorePaths.pickups(groupId, eventId, date)}/$uid"), "order", i) } }.await()
    }

    /** Q27: driver's consent flag on the day document. */
    suspend fun setSharing(groupId: String, eventId: String, date: String, on: Boolean) {
        db.document(FirestorePaths.day(groupId, eventId, date)).set(mapOf("pickupSharing" to on, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }
}
