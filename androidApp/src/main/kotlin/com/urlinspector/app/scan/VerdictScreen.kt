package com.urlinspector.app.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.urlinspector.core.model.ScanResult
import com.urlinspector.core.model.Verdict

@Composable
fun VerdictScreen(
    result: ScanResult,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val (label, color) = when (result.verdict) {
            Verdict.SAFE -> "Safe" to MaterialTheme.colorScheme.primary
            Verdict.SUSPICIOUS -> "Suspicious" to MaterialTheme.colorScheme.tertiary
            Verdict.MALICIOUS -> "Malicious" to MaterialTheme.colorScheme.error
        }

        Text(label, style = MaterialTheme.typography.headlineLarge, color = color)
        Text(result.finalUrl.normalized, style = MaterialTheme.typography.bodyMedium)

        if (!result.reputationResult.checked) {
            Text("Reputation check could not be completed — showing on-device checks only.")
        }

        if (result.heuristicFindings.isEmpty()) {
            Text("No issues found.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(result.heuristicFindings) { finding ->
                    Text("• ${finding.description}", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }

        Button(onClick = { onOpenLink(result.finalUrl.normalized) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open link anyway")
        }

        TextButton(onClick = onBack) {
            Text("Scan another link")
        }
    }
}
