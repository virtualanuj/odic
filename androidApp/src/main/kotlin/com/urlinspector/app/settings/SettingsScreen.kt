package com.urlinspector.app.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.urlinspector.app.sms.SmsContentObserverService
import org.koin.androidx.compose.koinViewModel

private fun hasReadSmsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val enabled by viewModel.smsScanningEnabled.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val readSmsGranted = grants[Manifest.permission.READ_SMS] == true
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grants[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }
        if (readSmsGranted && notificationsGranted) {
            viewModel.setSmsScanningEnabled(true)
            SmsContentObserverService.start(context)
        }
        // If either is denied, the toggle simply stays off — no error dialog needed for v1.
    }

    // Re-check on every resume: if the user revoked READ_SMS from system
    // Settings while this toggle was on, force it off and stop the
    // service — the second half of "revoking permission stops scanning."
    // Conversely, if the toggle is on and permission is still granted, make
    // sure the service is actually running — self-heals the case where the
    // preference survived a process death (or reboot) but the service didn't.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (enabled && !hasReadSmsPermission(context)) {
                    viewModel.setSmsScanningEnabled(false)
                    SmsContentObserverService.stop(context)
                } else if (enabled && hasReadSmsPermission(context)) {
                    SmsContentObserverService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Scan text messages for links")
                Text(
                    "Off by default. Watches incoming SMS for links and shows a scan result notification.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        val permissionsToRequest = mutableListOf(Manifest.permission.READ_SMS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(permissionsToRequest.toTypedArray())
                    } else {
                        viewModel.setSmsScanningEnabled(false)
                        SmsContentObserverService.stop(context)
                    }
                },
            )
        }

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}
