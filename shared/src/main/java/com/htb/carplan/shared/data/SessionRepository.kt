package com.htb.carplan.shared.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

data class Session(val uid: String, val soloGroupId: String)

/** 1장: after linking. [switchedAccount] = the Google account already owned another uid, so we signed into that one. */
data class LinkResult(val uid: String, val switchedAccount: Boolean)

/** Until login (1장) users are anonymous: "일행-1234" from the uid so members can tell each other apart. */
fun placeholderName(uid: String) = "일행-" + uid.filter { it.isLetterOrDigit() }.takeLast(4).uppercase()

/** Short codes for invites and friends: 6 chars, no 0/O/1/I confusion. */
object Codes {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    fun new(): String = (1..6).map { ALPHABET.random() }.joinToString("")
    fun normalize(raw: String): String = raw.trim().uppercase().replace('0', 'O').replace('1', 'I').filter { it.isLetterOrDigit() }
}

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
            // Older users: fill the placeholder name and friend code once.
            if (existing.name.isBlank() || existing.friendCode.isBlank()) {
                userRef.set(mapOf("name" to existing.name.ifBlank { placeholderName(uid) }, "friendCode" to existing.friendCode.ifBlank { Codes.new() }), com.google.firebase.firestore.SetOptions.merge()).await()
            }
            Session(uid, existing.soloGroupId)
        } else {
            val groupRef = db.collection(FirestorePaths.GROUPS).document()
            db.runBatch { batch ->
                batch.set(groupRef, Group(name = "나만", memberIds = listOf(uid), ownerUid = uid, solo = true))
                batch.set(userRef, (existing ?: User()).copy(soloGroupId = groupRef.id, name = placeholderName(uid), friendCode = Codes.new()))
            }.await()
            Session(uid, groupRef.id)
        }
        cachedSession = session
        session
    }

    // ---------- 1장 로그인 ----------

    /**
     * Google: link the Google credential to the current anonymous user so the uid (and every group, event, record)
     * stays. If that Google account is already a different Firebase user, sign into it instead ([LinkResult.switchedAccount]).
     */
    suspend fun linkGoogle(idToken: String, name: String?, photoUrl: String?): LinkResult {
        val cred = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
        val before = auth.currentUser
        var switched = false
        try {
            if (before != null && before.isAnonymous) before.linkWithCredential(cred).await()
            else { auth.signInWithCredential(cred).await(); switched = before != null && before.uid != auth.currentUser?.uid }
        } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            auth.signInWithCredential(cred).await(); switched = true
        }
        cachedSession = null
        val s = ensureSession()
        fillProfile(s.uid, name, photoUrl, "google")
        return LinkResult(s.uid, switched)
    }

    /** Kakao: Functions `kakaoLogin` already mapped the Kakao id to a uid (the caller's anonymous uid the first time). */
    suspend fun signInWithCustomToken(token: String, name: String?, photoUrl: String?): LinkResult {
        val before = auth.currentUser?.uid
        auth.signInWithCustomToken(token).await()
        cachedSession = null
        val s = ensureSession()
        fillProfile(s.uid, name, photoUrl, "kakao")
        return LinkResult(s.uid, before != null && before != s.uid)
    }

    fun isAnonymous(): Boolean = auth.currentUser?.isAnonymous != false

    /** Keep a real name over the "일행-1234" placeholder; never overwrite a name the user typed. */
    private suspend fun fillProfile(uid: String, name: String?, photoUrl: String?, provider: String) {
        val ref = db.document(FirestorePaths.user(uid))
        val cur = ref.get().await().toObject(User::class.java)
        val data = HashMap<String, Any>()
        data["provider"] = provider
        if (!name.isNullOrBlank() && (cur == null || cur.name.isBlank() || cur.name.startsWith("일행-"))) data["name"] = name.trim().take(20)
        if (!photoUrl.isNullOrBlank()) data["photoUrl"] = photoUrl
        ref.set(data, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    /** 설정 탭: the display name other members see (until login brings a real one, 1장). */
    suspend fun updateName(uid: String, name: String) {
        db.document(FirestorePaths.user(uid)).set(mapOf("name" to name.trim().take(20)), com.google.firebase.firestore.SetOptions.merge()).await()
    }

    /** 17장: keeps this device's push token on the user (arrayUnion, so several phones work). */
    suspend fun registerFcmToken(uid: String, token: String) {
        db.document(FirestorePaths.user(uid)).set(mapOf("fcmTokens" to com.google.firebase.firestore.FieldValue.arrayUnion(token)), com.google.firebase.firestore.SetOptions.merge()).await()
    }
}
