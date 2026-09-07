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
import com.urlinspector.app.settings.ScanPreferences
import com.urlinspector.core.ScanUrlUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject

class SmsContentObserverService : Service() {

    private val scanUrlUseCase: ScanUrlUseCase by inject()
    private val preferences: ScanPreferences by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processMutex = Mutex()

    private lateinit var coordinator: SmsScanCoordinator
    private lateinit var observer: ContentObserver
    private var lastSeenTimestampMillis = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        coordinator = SmsScanCoordinator(scanUrlUseCase, AndroidScanNotifier(this))

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                serviceScope.launch { processNewMessages() }
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)

        startForeground(FOREGROUND_NOTIFICATION_ID, buildStatusNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun processNewMessages() {
        processMutex.withLock {
            try {
                val bodies = mutableListOf<String>()

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
                        val body = cursor.getString(bodyIndex)
                        if (body != null) {
                            bodies.add(body)
                        }
                    }
                }

                for (body in bodies) {
                    coordinator.processMessageBody(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // READ_SMS was revoked while this service was running (e.g.
                // via system Settings) — stop scanning immediately rather
                // than continuing to fail silently on every future change,
                // and clear the persisted toggle so Settings doesn't keep
                // showing "on" for a service that is no longer running.
                preferences.setSmsScanningEnabled(false)
                stopSelf()
            } catch (e: Exception) {
                // A null body column, a missing column on some OEM provider
                // (getColumnIndexOrThrow), or a misbehaving provider throwing
                // SQLiteException must not propagate out of this coroutine —
                // there is no CoroutineExceptionHandler on serviceScope, so an
                // uncaught exception here would crash the whole app process.
            }
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
