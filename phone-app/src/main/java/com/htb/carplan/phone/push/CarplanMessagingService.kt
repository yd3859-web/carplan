package com.htb.carplan.phone.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.htb.carplan.phone.MainActivity
import com.htb.carplan.shared.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 17장: receives pushes from Cloud Functions and keeps the token on users/{uid}. */
class CarplanMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            CoroutineScope(Dispatchers.IO).launch { runCatching { SessionRepository().registerFcmToken(uid, token) } }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "carplan"
        val body = message.notification?.body ?: message.data["body"] ?: return
        show(this, title, body, message.data)
    }

    companion object {
        const val CHANNEL = "carplan"

        /** Called once after sign-in so the token is on the user even without a refresh. */
        suspend fun syncToken(uid: String) {
            runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()?.let { SessionRepository().registerFcmToken(uid, it) }
        }

        fun show(context: Context, title: String, body: String, data: Map<String, String>) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "일정·픽업·기록 알림", NotificationManager.IMPORTANCE_DEFAULT))
            // Tapping opens the app on the trip the push is about (handled like an invite link: carplan://trip/...).
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                data["groupId"]?.let { g ->
                    val parking = data["stopId"]?.takeIf { data["kind"] == "parking" }?.let { "?parking=$it" } ?: ""
                    setData(android.net.Uri.parse("carplan://trip/$g/${data["eventId"] ?: ""}/${data["date"] ?: ""}$parking"))
                }
            }
            val pi = PendingIntent.getActivity(context, body.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(pi).build()
            nm.notify(body.hashCode(), n)
        }
    }
}
