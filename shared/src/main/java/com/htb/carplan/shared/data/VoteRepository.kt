package com.htb.carplan.shared.data

import com.google.firebase.firestore.FirebaseFirestore
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Vote
import com.htb.carplan.shared.model.VoteStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** 5장 투표. Closes itself on a majority, or when everyone voted (most votes; ties keep both, Q12). */
class VoteRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    private val chat = ChatRepository(db)

    fun observe(groupId: String): Flow<List<Vote>> = callbackFlow {
        val reg = db.collection(FirestorePaths.votes(groupId)).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObjects(Vote::class.java).orEmpty().sortedWith(compareBy({ it.status != VoteStatus.OPEN }, { -(it.createdAt?.seconds ?: 0) })))
        }
        awaitClose { reg.remove() }
    }

    suspend fun create(groupId: String, question: String, options: List<String>, places: List<Place?>, eventId: String?, date: String?, byUid: String): String {
        val ref = db.collection(FirestorePaths.votes(groupId)).document()
        val v = Vote(question = question.trim(), options = options, places = places, eventId = eventId, date = date, createdBy = byUid)
        ref.set(v).await()
        // 채팅방에도 "투표가 열렸어요" 카드 (A안 2026-11-03). A chat failure never undoes the vote.
        runCatching { chat.voteOpened(groupId, ref.id, v) }
        return ref.id
    }

    /** One ballot per person; re-voting replaces it. Then applies the closing rules against [memberCount]. */
    suspend fun cast(groupId: String, voteId: String, uid: String, option: Int, memberCount: Int) {
        val ref = db.document("${FirestorePaths.votes(groupId)}/$voteId")
        val closed = db.runTransaction<Vote?> { tx ->
            val v = tx.get(ref).toObject(Vote::class.java) ?: return@runTransaction null
            if (v.status != VoteStatus.OPEN) return@runTransaction null
            val ballots = v.ballots + (uid to option)
            val counts = v.options.indices.map { i -> ballots.values.count { it == i } }
            val top = counts.maxOrNull() ?: 0
            val majority = top * 2 > memberCount
            val everyone = ballots.size >= memberCount
            val updates = mutableMapOf<String, Any>("ballots" to ballots)
            var done: Vote? = null
            if (majority || everyone) {
                val winners = counts.indices.filter { counts[it] == top }
                updates["status"] = VoteStatus.CLOSED.name
                updates["winners"] = winners
                done = v.copy(ballots = ballots, status = VoteStatus.CLOSED, winners = winners)
            }
            tx.update(ref, updates)
            done
        }.await()
        if (closed != null) runCatching { chat.voteClosed(groupId, voteId, closed) }
    }

    /** 그룹장 or the creator ends it by hand. */
    suspend fun close(groupId: String, voteId: String) {
        val ref = db.document("${FirestorePaths.votes(groupId)}/$voteId")
        val closed = db.runTransaction<Vote?> { tx ->
            val v = tx.get(ref).toObject(Vote::class.java) ?: return@runTransaction null
            val counts = v.options.indices.map { i -> v.ballots.values.count { it == i } }
            val top = counts.maxOrNull() ?: 0
            val winners = counts.indices.filter { counts[it] == top && top > 0 }
            tx.update(ref, mapOf("status" to VoteStatus.CLOSED.name, "winners" to winners))
            v.copy(status = VoteStatus.CLOSED, winners = winners)
        }.await()
        if (closed != null) runCatching { chat.voteClosed(groupId, voteId, closed) }
    }
}
