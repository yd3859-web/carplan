package com.htb.carplan.shared.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import com.htb.carplan.shared.model.Comment
import com.htb.carplan.shared.model.DayStatus
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop
import com.htb.carplan.shared.model.TripDay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * One day of a trip (3장): its stops and the day's version counter.
 *
 * Every edit is one batch that also bumps `TripDay.version` and records who changed it,
 * so the car (10장) can tell that something is new. Edits are last-write-wins (Q07).
 * Stops are never deleted, only marked `deleted` (undo + "○○님이 △△를 뺐어요").
 */
class TripRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /** All stops of the day in route order, including soft-deleted ones (the UI decides what to show). */
    fun observeStops(groupId: String, eventId: String, date: String): Flow<List<Stop>> = callbackFlow {
        val reg = db.collection(FirestorePaths.stops(groupId, eventId, date))
            .orderBy("order")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                trySend(snap?.toObjects(Stop::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    fun observeDay(groupId: String, eventId: String, date: String): Flow<TripDay?> = callbackFlow {
        val reg = db.document(FirestorePaths.day(groupId, eventId, date))
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                trySend(snap?.toObject(TripDay::class.java))
            }
        awaitClose { reg.remove() }
    }

    /** Appends a stop after the last live one. Returns the new stop id. */
    suspend fun addStop(
        groupId: String, eventId: String, date: String,
        place: Place, time: String?, memo: String, byUid: String,
    ): String {
        val stops = db.collection(FirestorePaths.stops(groupId, eventId, date))
        val live = stops.get().await().toObjects(Stop::class.java).filterNot { it.deleted }
        val ref = stops.document()
        db.runBatch { batch ->
            batch.set(
                ref,
                Stop(order = (live.maxOfOrNull { it.order } ?: -1) + 1, place = place, time = time, memo = memo, createdBy = byUid, updatedBy = byUid),
            )
            batch.bumpDay(groupId, eventId, date, byUid)
        }.await()
        return ref.id
    }

    /** Changes place / time / memo of one stop (last write wins). */
    suspend fun updateStop(
        groupId: String, eventId: String, date: String, stopId: String,
        place: Place, time: String?, memo: String, byUid: String,
    ) {
        db.runBatch { batch ->
            batch.update(
                db.document("${FirestorePaths.stops(groupId, eventId, date)}/$stopId"),
                mapOf(
                    "place" to place,
                    "time" to time,
                    "memo" to memo,
                    "updatedBy" to byUid,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.bumpDay(groupId, eventId, date, byUid)
        }.await()
    }

    /**
     * 픽업 칸 (정원 2026-11-09): [uid]'s pickup place as an ordinary slot. Moves the existing slot's place, or puts a new
     * one before the places still to go (after other pickups not yet reached) — the group can then drag or 동선 정렬 it.
     */
    suspend fun upsertPickupStop(groupId: String, eventId: String, date: String, uid: String, name: String, place: Place, byUid: String) {
        val col = db.collection(FirestorePaths.stops(groupId, eventId, date))
        val live = col.get().await().toObjects(Stop::class.java).filterNot { it.deleted }.sortedBy { it.order }
        val memo = "${name.ifBlank { "일행" }} 픽업"
        val mine = live.firstOrNull { it.pickupUid == uid }
        if (mine != null) {
            db.runBatch { b ->
                b.update(col.document(mine.id), mapOf("place" to place, "memo" to memo, "updatedBy" to byUid, "updatedAt" to FieldValue.serverTimestamp()))
                b.bumpDay(groupId, eventId, date, byUid)
            }.await()
            return
        }
        val firstAhead = live.indexOfFirst { !it.visited && it.pickupUid == null }.let { if (it < 0) live.size else it }
        val ref = col.document()
        val ordered = live.map { it.id }.toMutableList().apply { add(firstAhead, ref.id) }
        db.runBatch { b ->
            b.set(ref, Stop(order = firstAhead, place = place, memo = memo, createdBy = byUid, updatedBy = byUid, pickupUid = uid))
            ordered.forEachIndexed { i, id -> if (id != ref.id) b.update(col.document(id), "order", i) }
            b.bumpDay(groupId, eventId, date, byUid)
        }.await()
    }

    /** The pickup was taken back: its slot goes too (soft delete, like any slot). */
    suspend fun removePickupStop(groupId: String, eventId: String, date: String, uid: String, byUid: String) {
        val col = db.collection(FirestorePaths.stops(groupId, eventId, date))
        val mine = col.get().await().toObjects(Stop::class.java).filter { it.pickupUid == uid && !it.deleted }
        if (mine.isEmpty()) return
        db.runBatch { b ->
            mine.forEach { b.update(col.document(it.id), mapOf("deleted" to true, "updatedBy" to byUid, "updatedAt" to FieldValue.serverTimestamp())) }
            b.bumpDay(groupId, eventId, date, byUid)
        }.await()
    }

    /** Rewrites `order` for the given stop ids in list order (drag reorder or 동선 정렬). */
    suspend fun reorder(groupId: String, eventId: String, date: String, orderedIds: List<String>, byUid: String) {
        db.runBatch { batch ->
            orderedIds.forEachIndexed { i, id ->
                batch.update(db.document("${FirestorePaths.stops(groupId, eventId, date)}/$id"), "order", i)
            }
            batch.bumpDay(groupId, eventId, date, byUid)
        }.await()
    }

    suspend fun setDeleted(groupId: String, eventId: String, date: String, stopId: String, deleted: Boolean, byUid: String) {
        db.runBatch { batch ->
            batch.update(
                db.document("${FirestorePaths.stops(groupId, eventId, date)}/$stopId"),
                mapOf("deleted" to deleted, "updatedBy" to byUid, "updatedAt" to FieldValue.serverTimestamp()),
            )
            batch.bumpDay(groupId, eventId, date, byUid)
        }.await()
    }

    /** Every comment of the day, oldest first; the UI groups them by [Comment.stopId]. */
    fun observeDayComments(groupId: String, eventId: String, date: String): Flow<List<Comment>> = callbackFlow {
        val reg = db.collection(FirestorePaths.dayComments(groupId, eventId, date))
            .orderBy("createdAt")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                trySend(snap?.toObjects(Comment::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    /** A member's note under [stopId] ("여기서 뭐 먹을까?"). Does not bump the day version: the route is unchanged. */
    suspend fun addComment(groupId: String, eventId: String, date: String, stopId: String, text: String, byUid: String) {
        db.collection(FirestorePaths.dayComments(groupId, eventId, date))
            .add(Comment(uid = byUid, text = text.trim(), stopId = stopId)).await()
    }

    /** The car reached (or skipped) a stop (9장). Does not bump the version: the itinerary itself did not change. */
    suspend fun setVisited(groupId: String, eventId: String, date: String, stopId: String, visited: Boolean, nextIndex: Int, skipped: Boolean = false) {
        db.runBatch { batch ->
            batch.update(db.document("${FirestorePaths.stops(groupId, eventId, date)}/$stopId"), mapOf("visited" to visited, "skipped" to (visited && skipped)))
            batch.set(
                db.document(FirestorePaths.day(groupId, eventId, date)),
                mapOf("currentStopIndex" to nextIndex, "updatedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            )
        }.await()
    }

    /** Car side (10장): the route now matches [syncedVersion] (or the driver held the change), plus the next ETA. */
    suspend fun setRouteSync(groupId: String, eventId: String, date: String, syncedVersion: Long, hold: Boolean, etaNext: String?) {
        db.document(FirestorePaths.day(groupId, eventId, date))
            .set(mapOf("routeSyncedVersion" to syncedVersion, "holdByDriver" to hold, "etaNext" to etaNext, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    /** 12장: the car is ~5 minutes from [stopId] → passengers get "주차장 찾아봐 주세요" (Functions watch this field). */
    /** Q55: the car's fuel plan for the day, shown on the phones' rail (null clears it). */
    suspend fun setFuelNote(groupId: String, eventId: String, date: String, note: com.htb.carplan.shared.model.FuelNote?) {
        db.document(FirestorePaths.day(groupId, eventId, date)).set(mapOf("fuelNote" to note), SetOptions.merge()).await()
    }

    suspend fun setParkingHint(groupId: String, eventId: String, date: String, stopId: String?) {
        db.document(FirestorePaths.day(groupId, eventId, date))
            .set(mapOf("parkingHintStopId" to stopId, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    /** READY → DRIVING when the car starts guiding, → DONE after the last stop (9장). */
    suspend fun setDayStatus(groupId: String, eventId: String, date: String, status: DayStatus) {
        db.document(FirestorePaths.day(groupId, eventId, date))
            .set(mapOf("status" to status.name, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    private fun WriteBatch.bumpDay(groupId: String, eventId: String, date: String, byUid: String) {
        set(
            db.document(FirestorePaths.day(groupId, eventId, date)),
            mapOf(
                "version" to FieldValue.increment(1),
                "lastChangedBy" to byUid,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        )
    }
}
