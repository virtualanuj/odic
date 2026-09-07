package com.urlinspector.app.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.urlinspector.app.ShareHandlerActivity
import com.urlinspector.core.model.Verdict

class AndroidScanNotifier(private val context: Context) : ScanNotifier {

    override fun notify(url: String, verdict: Verdict) {
        val title = when (verdict) {
            Verdict.SAFE -> "Safe link found in a text message"
            Verdict.SUSPICIOUS -> "Suspicious link found in a text message"
            Verdict.MALICIOUS -> "⚠️ Malicious link found in a text message"
        }

        // Reuses ShareHandlerActivity's existing ACTION_SEND/EXTRA_TEXT
        // handling (M4) — the exact same entry point WhatsApp/Messages
        // shares use, so tapping this notification gets the identical
        // auto-scan-to-verdict flow, with no new Activity needed.
        val tapIntent = Intent(context, ShareHandlerActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            url.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ID_RESULTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(url)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // NotificationManagerCompat.notify() is a documented no-op (not a
        // crash) if POST_NOTIFICATIONS isn't granted on API 33+ — no
        // explicit permission check needed here.
        NotificationManagerCompat.from(context).notify(url.hashCode(), notification)
    }
}
