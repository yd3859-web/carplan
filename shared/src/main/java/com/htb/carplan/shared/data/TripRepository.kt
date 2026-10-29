package com.htb.carplan.shared.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.htb.carplan.shared.model.Stop
import com.htb.carplan.shared.model.TripDay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Reads and edits one trip day: its stops and its version counter. */
class TripRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /** Live list of the day's stops in route order, soft-deleted ones excluded. */
    fun observeStops(groupId: String, tripId: String, date: String): Flow<List<Stop>> = callbackFlow {
        val reg = db.collection(FirestorePaths.stops(groupId, tripId, date))
            .orderBy("order")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val stops = snap?.toObjects(Stop::class.java).orEmpty().filterNot { it.deleted }
                trySend(stops)
            }
        awaitClose { reg.remove() }
    }

    fun observeDay(groupId: String, tripId: String, date: String): Flow<TripDay?> = callbackFlow {
        val reg = db.document(FirestorePaths.day(groupId, tripId, date))
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                trySend(snap?.toObject(TripDay::class.java))
            }
        awaitClose { reg.remove() }
    }

    /** Appends a stop and bumps the day version in one batch. */
    suspend fun addStop(groupId: String, tripId: String, date: String, stop: Stop, byUid: String) {
        val stops = db.collection(FirestorePaths.stops(groupId, tripId, date))
        val nextOrder = stops.get().await().size()
        db.runBatch { batch ->
            batch.set(stops.document(), stop.copy(order = nextOrder, createdBy = byUid))
            batch.set(
                db.document(FirestorePaths.day(groupId, tripId, date)),
                mapOf(
                    "version" to FieldValue.increment(1),
                    "lastChangedBy" to byUid,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
        }.await()
    }
}
