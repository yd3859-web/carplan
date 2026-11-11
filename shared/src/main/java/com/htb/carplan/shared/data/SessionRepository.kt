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

    /**
     * 시연 계정 (2026-11-11, 심사위원·시연 영상용): sign into the fixed demo owner — the account that holds the made-up
     * 11월 (차 연결·그룹·여행·기록). This device's anonymous account is left behind.
     */
    suspend fun signInDemo(password: String): String {
        auth.signInWithEmailAndPassword(DemoAccounts.OWNER, password).await()
        cachedSession = null
        return ensureSession().uid
    }

    /** Makes the current (anonymous) account the demo owner, keeping its uid and data: done once, on the owner's phone. */
    suspend fun becomeDemo(password: String) {
        val user = auth.currentUser ?: error("로그인 정보가 없어요")
        user.linkWithCredential(com.google.firebase.auth.EmailAuthProvider.getCredential(DemoAccounts.OWNER, password)).await()
        fillProfile(user.uid, null, null, "demo")
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

/**
 * 시연 계정 (2026-11-11): fixed e-mail addresses (the domain only names them; nothing is sent there). The password is
 * in local.properties (DEMO_PASSWORD), never in git. [FRIENDS] are the made-up friends of the 시연 data, by index —
 * 민지 0 · 준호 1 · 서연 2 · 엄마 3 · 지훈 4 · 아빠 5 ([com.htb.carplan.car] DemoSeed).
 */
object DemoAccounts {
    const val OWNER = "owner@carplan-demo.app"
    val FRIENDS = listOf("minji", "junho", "seoyeon", "mom", "jihoon", "dad").map { "$it@carplan-demo.app" }
}
