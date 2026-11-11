package com.htb.carplan.shared.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.htb.carplan.shared.model.ChatKind
import com.htb.carplan.shared.model.ChatMessage
import com.htb.carplan.shared.model.Dart
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Vote
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** 여행별 채팅방 (팀장 2026-11-03 A안): words and cards (다트 결과 · 투표) in one stream per group. */
class ChatRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /** The latest [limit] lines, oldest first. A line still waiting for its server time sorts last. */
    fun observe(groupId: String, limit: Long = 300): Flow<List<ChatMessage>> = callbackFlow {
        val reg = db.collection(FirestorePaths.chat(groupId))
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(ChatMessage::class.java).orEmpty().reversed())
            }
        awaitClose { reg.remove() }
    }

    suspend fun send(groupId: String, uid: String, text: String, eventId: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        db.collection(FirestorePaths.chat(groupId)).add(ChatMessage(uid = uid, text = t.take(1000), eventId = eventId)).await()
    }

    /** 다트 결과를 채팅에: "경주시 황남동" + [칸으로]. One card per throw (the same throw twice is one card). */
    suspend fun shareDart(groupId: String, d: Dart, uid: String) {
        val parts = d.resultName.split(" ")
        val place = Place(name = parts.last(), lat = d.resultLat, lng = d.resultLng, address = d.resultName)
        db.collection(FirestorePaths.chat(groupId)).document("dart-${d.id}-${d.throwCount}")
            .set(ChatMessage(uid = uid, kind = ChatKind.DART, text = d.resultName, place = place, refId = d.id, eventId = d.eventId, date = d.date)).await()
    }

    /**
     * 장소 카드: [+] → 장소, 말풍선 길게 → 장소 찾기, 또는 칸에서 [채팅에 물어보기]([ask] = "여기 어때?").
     * 받은 사람은 [칸으로]·[투표 후보로].
     */
    suspend fun sharePlace(groupId: String, uid: String, place: Place, eventId: String?, date: String?, ask: Boolean = false, refId: String? = null) {
        db.collection(FirestorePaths.chat(groupId))
            .add(ChatMessage(uid = uid, kind = ChatKind.PLACE, text = place.name, place = place, refId = refId, lines = if (ask) listOf("여기 어때?") else emptyList(), eventId = eventId, date = date)).await()
    }

    /** 투표가 열리면 카드 하나 (투표 문서 id로 한 장만). */
    suspend fun voteOpened(groupId: String, voteId: String, v: Vote) {
        db.collection(FirestorePaths.chat(groupId)).document("vote-$voteId")
            .set(ChatMessage(uid = v.createdBy, kind = ChatKind.VOTE_OPEN, text = v.question, refId = voteId, lines = v.options, eventId = v.eventId, date = v.date)).await()
    }

    /** 투표가 끝나면 결과 카드 (이긴 곳이 장소면 [칸으로]). */
    suspend fun voteClosed(groupId: String, voteId: String, v: Vote) {
        val winners = v.winners.mapNotNull { v.options.getOrNull(it) }
        val place = v.winners.singleOrNull()?.let { v.places.getOrNull(it) }
        db.collection(FirestorePaths.chat(groupId)).document("vote-$voteId-closed")
            .set(ChatMessage(uid = v.createdBy, kind = ChatKind.VOTE_CLOSED, text = v.question, place = place, refId = voteId, lines = winners, eventId = v.eventId, date = v.date)).await()
    }
}
