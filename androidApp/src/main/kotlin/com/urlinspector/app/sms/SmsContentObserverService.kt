package com.urlinspector.app.sms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.urlinspector.core.ScanUrlUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class SmsContentObserverService : Service() {

    private val scanUrlUseCase: ScanUrlUseCase by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var coordinator: SmsScanCoordinator
    private lateinit var observer: ContentObserver
    private var lastSeenTimestampMillis = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        coordinator = SmsScanCoordinator(scanUrlUseCase, AndroidScanNotifier(this))

        startForeground(FOREGROUND_NOTIFICATION_ID, buildStatusNotification())

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                serviceScope.launch { processNewMessages() }
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun processNewMessages() {
        try {
            contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(lastSeenTimestampMillis.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { cursor ->
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (cursor.moveToNext()) {
                    lastSeenTimestampMillis = maxOf(lastSeenTimestampMillis, cursor.getLong(dateIndex))
                    coordinator.processMessageBody(cursor.getString(bodyIndex))
                }
            }
        } catch (e: SecurityException) {
            // READ_SMS was revoked while this service was running (e.g.
            // via system Settings) — stop scanning immediately rather
            // than continuing to fail silently on every future change.
            stopSelf()
        }
    }

    private fun buildStatusNotification() =
        NotificationCompat.Builder(this, NotificationChannels.CHANNEL_ID_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("URL Inspector")
            .setContentText("Watching for links in text messages")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 2001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SmsContentObserverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsContentObserverService::class.java))
        }
    }
}
