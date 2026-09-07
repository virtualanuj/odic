package com.urlinspector.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AppRoot(
    onOpenLink: (String) -> Unit,
    sharedUrl: String? = null,
    prefillText: String? = null,
) {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AppNavHost(
                onOpenLink = onOpenLink,
                sharedUrl = sharedUrl,
                prefillText = prefillText,
            )
        }
    }
}
