package com.htb.carplan.shared.data

import com.google.firebase.firestore.FirebaseFirestore
import com.htb.carplan.shared.model.Dart
import com.htb.carplan.shared.model.DartScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** 6장 다트 방: one document per room; every throw rewrites the result so everyone sees it at once. */
class DartRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /** The group's newest room (null until someone throws). */
    fun observeLatest(groupId: String): Flow<Dart?> = callbackFlow {
        val reg = db.collection(FirestorePaths.darts(groupId)).orderBy("thrownAt", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(Dart::class.java)?.firstOrNull())
            }
        awaitClose { reg.remove() }
    }

    /** Writes a throw. [roomId] null = new room. Returns the room id. */
    suspend fun throwDart(groupId: String, roomId: String?, scope: DartScope, centerLat: Double?, centerLng: Double?, region: String?, result: Regions.Region, throwCount: Int, byUid: String, eventId: String?, date: String?): String {
        val ref = roomId?.let { db.document("${FirestorePaths.darts(groupId)}/$it") } ?: db.collection(FirestorePaths.darts(groupId)).document()
        ref.set(
            Dart(scope = scope, centerLat = centerLat, centerLng = centerLng, region = region, resultName = result.fullName, resultLat = result.lat, resultLng = result.lng, throwCount = throwCount, thrownBy = byUid, eventId = eventId, date = date),
        ).await()
        return ref.id
    }
}
