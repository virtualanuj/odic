package com.urlinspector.app.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

object NotificationChannels {
    const val CHANNEL_ID_STATUS = "sms_scan_status"
    const val CHANNEL_ID_RESULTS = "sms_scan_results"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager: NotificationManager = context.getSystemService() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_STATUS,
                "SMS scanning status",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Shows while URL Inspector is watching incoming SMS messages for links."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_RESULTS,
                "SMS link scan results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "A link was found in an SMS message and scanned."
            },
        )
    }
}
