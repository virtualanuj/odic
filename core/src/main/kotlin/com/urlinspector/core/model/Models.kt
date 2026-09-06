package com.urlinspector.core.model

import java.time.Instant

data class ScannedUrl(
    val raw: String,
    val normalized: String,
    val host: String,
)

enum class Verdict {
    SAFE,
    SUSPICIOUS,
    MALICIOUS,
}

data class HeuristicFinding(
    val id: String,
    val description: String,
)

data class ReputationResult(
    val matched: Boolean,
    val source: String,
    val checked: Boolean,
)

data class ScanResult(
    val url: ScannedUrl,
    val verdict: Verdict,
    val heuristicFindings: List<HeuristicFinding>,
    val reputationResult: ReputationResult,
    val scannedAt: Instant,
    val finalUrl: ScannedUrl = url,
)

data class ScanHistoryEntry(
    val id: String,
    val url: String,
    val verdict: Verdict,
    val scannedAt: Instant,
)
