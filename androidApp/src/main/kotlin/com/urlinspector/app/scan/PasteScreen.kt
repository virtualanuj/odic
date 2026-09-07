package com.urlinspector.app.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PasteScreen(
    uiState: ScanUiState,
    onScan: (String) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var urlText by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("URL Inspector", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Paste a link to check") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = { onScan(urlText) },
            enabled = urlText.isNotBlank() && uiState !is ScanUiState.Loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState is ScanUiState.Loading) "Scanning…" else "Scan")
        }

        if (uiState is ScanUiState.Error) {
            Text(
                text = uiState.message,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TextButton(onClick = onOpenHistory) {
            Text("View scan history")
        }
    }
}
