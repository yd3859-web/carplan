package com.htb.carplan.shared.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.tasks.await

data class Session(val uid: String, val soloGroupId: String)

/**
 * Signs in and makes sure the user has a solo group (Q01).
 * Anonymous until login is built (맨 뒤); linking Google later keeps the same uid.
 */
class SessionRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    suspend fun ensureSession(): Session {
        val uid = auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid
        val userRef = db.document(FirestorePaths.user(uid))
        val existing = userRef.get().await().toObject(User::class.java)
        if (existing != null && existing.soloGroupId.isNotEmpty()) return Session(uid, existing.soloGroupId)

        val groupRef = db.collection(FirestorePaths.GROUPS).document()
        db.runBatch { batch ->
            batch.set(groupRef, Group(name = "나만", memberIds = listOf(uid), ownerUid = uid, solo = true))
            batch.set(userRef, (existing ?: User()).copy(soloGroupId = groupRef.id))
        }.await()
        return Session(uid, groupRef.id)
    }
}
