package com.htb.carplan.shared.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

data class Session(val uid: String, val soloGroupId: String)

// Screens and services start together; without one lock each would sign in
// anonymously on its own and the app would end up with two accounts.
private val signInLock = Mutex()
private val sessionLock = Mutex()
private var cachedSession: Session? = null

/** Signs in anonymously once per install and returns the uid (the car app uses this as its car id). */
suspend fun FirebaseAuth.ensureSignedIn(): String = signInLock.withLock {
    currentUser?.uid ?: signInAnonymously().await().user!!.uid
}

/**
 * Signs in and makes sure the user has a solo group (Q01).
 * Anonymous until login is built (맨 뒤); linking Google later keeps the same uid.
 */
class SessionRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    suspend fun ensureSession(): Session = sessionLock.withLock {
        cachedSession?.let { return@withLock it }
        val uid = auth.ensureSignedIn()
        val userRef = db.document(FirestorePaths.user(uid))
        val existing = userRef.get().await().toObject(User::class.java)
        val session = if (existing != null && existing.soloGroupId.isNotEmpty()) {
            Session(uid, existing.soloGroupId)
        } else {
            val groupRef = db.collection(FirestorePaths.GROUPS).document()
            db.runBatch { batch ->
                batch.set(groupRef, Group(name = "나만", memberIds = listOf(uid), ownerUid = uid, solo = true))
                batch.set(userRef, (existing ?: User()).copy(soloGroupId = groupRef.id))
            }.await()
            Session(uid, groupRef.id)
        }
        cachedSession = session
        session
    }
}
