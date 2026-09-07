package com.urlinspector.app.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.urlinspector.core.model.ScanHistoryEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val historyTimestampFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("MMM d, yyyy h:mm a")
    .withZone(ZoneId.systemDefault())

@Composable
fun HistoryScreen(
    entries: List<ScanHistoryEntry>,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Scan History", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onClearAll, enabled = entries.isNotEmpty()) {
                Text("Clear all")
            }
        }

        if (entries.isEmpty()) {
            Text("No scans yet.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.url, style = MaterialTheme.typography.bodyMedium)
                            Text(entry.verdict.name, style = MaterialTheme.typography.labelMedium)
                            Text(
                                historyTimestampFormatter.format(entry.scannedAt),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { onDelete(entry.id) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}
