package com.htb.carplan.shared.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.htb.carplan.shared.model.Friend
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * 4장 그룹·초대 and 7장 "오늘 이 차로 가요". Groups outlive one trip: the same members
 * share many days. Joining by code, link or QR all end in [joinByCode].
 */
class GroupRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    fun observeGroup(groupId: String): Flow<Group?> = callbackFlow {
        val reg = db.document(FirestorePaths.group(groupId)).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObject(Group::class.java))
        }
        awaitClose { reg.remove() }
    }

    /** Creates a shared group owned by [ownerUid]; returns its id. */
    suspend fun createGroup(name: String, ownerUid: String): String {
        val ref = db.collection(FirestorePaths.GROUPS).document()
        ref.set(Group(name = name.trim(), memberIds = listOf(ownerUid), ownerUid = ownerUid, inviteCode = Codes.new())).await()
        return ref.id
    }

    /** The group behind an invite code, or null. */
    suspend fun findByCode(code: String): Group? =
        db.collection(FirestorePaths.GROUPS).whereEqualTo("inviteCode", Codes.normalize(code)).limit(1)
            .get().await().toObjects(Group::class.java).firstOrNull()

    /** Joins the group with [code]; returns the group id, or null when no group has that code. */
    suspend fun joinByCode(code: String, uid: String): String? {
        val g = findByCode(code) ?: return null
        join(g.id, uid)
        return g.id
    }

    suspend fun join(groupId: String, uid: String) {
        db.document(FirestorePaths.group(groupId)).update("memberIds", FieldValue.arrayUnion(uid)).await()
    }

    /** Leaving (anyone) or removing someone (owner only, Q11). */
    suspend fun removeMember(groupId: String, uid: String) {
        db.document(FirestorePaths.group(groupId)).update("memberIds", FieldValue.arrayRemove(uid)).await()
    }

    /** Owner: add a friend straight into the group (4장 2-③). */
    suspend fun invite(groupId: String, friendUid: String) = join(groupId, friendUid)

    suspend fun rename(groupId: String, name: String) {
        db.document(FirestorePaths.group(groupId)).update("name", name.trim()).await()
    }

    // ---------- friends (Q10) ----------

    fun observeFriends(uid: String): Flow<List<Friend>> = callbackFlow {
        val reg = db.collection(FirestorePaths.friends(uid)).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObjects(Friend::class.java).orEmpty())
        }
        awaitClose { reg.remove() }
    }

    /** Adds the person whose friend code is [code] (both directions). Returns their uid, or null if no match. */
    suspend fun addFriendByCode(code: String, myUid: String): String? {
        val other = db.collection(FirestorePaths.USERS).whereEqualTo("friendCode", Codes.normalize(code)).limit(1)
            .get().await().documents.firstOrNull() ?: return null
        if (other.id == myUid) return null
        db.runBatch { b ->
            b.set(db.document("${FirestorePaths.friends(myUid)}/${other.id}"), Friend(), SetOptions.merge())
            b.set(db.document("${FirestorePaths.friends(other.id)}/$myUid"), Friend(), SetOptions.merge())
        }.await()
        return other.id
    }

    suspend fun user(uid: String): User? = db.document(FirestorePaths.user(uid)).get().await().toObject(User::class.java)

    // ---------- 7장 2: "오늘 이 차로 가요" ----------

    suspend fun setTripCar(groupId: String, eventId: String, carId: String?, driverUid: String?) {
        db.document(FirestorePaths.event(groupId, eventId)).update(mapOf("carId" to carId, "driverUid" to driverUid)).await()
    }
}
