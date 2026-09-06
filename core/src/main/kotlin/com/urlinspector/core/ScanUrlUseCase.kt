package com.urlinspector.core

import com.urlinspector.core.heuristics.HomographHeuristic
import com.urlinspector.core.heuristics.IpLiteralHeuristic
import com.urlinspector.core.heuristics.ShortenerHeuristic
import com.urlinspector.core.heuristics.SuspiciousTldHeuristic
import com.urlinspector.core.heuristics.TyposquatHeuristic
import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.ScanResult
import com.urlinspector.core.model.ScannedUrl
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID

class InvalidUrlException(raw: String) : IllegalArgumentException("Not a valid URL: $raw")

class ScanUrlUseCase(
    private val reputationProvider: ReputationProvider,
    private val urlExpander: UrlExpander,
    private val scanRepository: ScanRepository,
    private val reputationTimeoutMillis: Long = 5_000L,
    private val now: () -> Instant = Instant::now,
) {
    private val heuristics: List<(ScannedUrl) -> HeuristicFinding?> = listOf(
        TyposquatHeuristic::evaluate,
        SuspiciousTldHeuristic::evaluate,
        ShortenerHeuristic::evaluate,
        IpLiteralHeuristic::evaluate,
        HomographHeuristic::evaluate,
    )

    suspend fun scan(rawUrl: String): ScanResult {
        val url = normalizeUrl(rawUrl) ?: throw InvalidUrlException(rawUrl)

        val findings = mutableListOf<HeuristicFinding>()
        var reputationResult = ReputationResult(matched = false, source = reputationProvider.id, checked = false)

        coroutineScope {
            val heuristicDeferreds = heuristics.map { heuristic -> async { heuristic(url) } }
            val reputationDeferred = async { checkReputation(url) }

            heuristicDeferreds.forEach { deferred -> deferred.await()?.let { findings.add(it) } }
            reputationResult = reputationDeferred.await()
        }

        val sawShortener = findings.any { it.id == "shortener" }
        if (sawShortener) {
            val expandedUrl = urlExpander.expand(url)
            if (expandedUrl.normalized != url.normalized) {
                coroutineScope {
                    val typosquatDeferred = async { TyposquatHeuristic.evaluate(expandedUrl) }
                    val tldDeferred = async { SuspiciousTldHeuristic.evaluate(expandedUrl) }
                    val reputationDeferred = async { checkReputation(expandedUrl) }

                    typosquatDeferred.await()?.let { findings.add(it) }
                    tldDeferred.await()?.let { findings.add(it) }

                    val expandedReputation = reputationDeferred.await()
                    if (expandedReputation.matched) {
                        reputationResult = expandedReputation
                    } else if (!reputationResult.checked && expandedReputation.checked) {
                        reputationResult = expandedReputation
                    }
                }
            }
        }

        val verdict = when {
            reputationResult.matched -> Verdict.MALICIOUS
            findings.isNotEmpty() -> Verdict.SUSPICIOUS
            else -> Verdict.SAFE
        }

        val scannedAt = now()
        val result = ScanResult(
            url = url,
            verdict = verdict,
            heuristicFindings = findings,
            reputationResult = reputationResult,
            scannedAt = scannedAt,
        )

        scanRepository.save(
            ScanHistoryEntry(
                id = UUID.randomUUID().toString(),
                url = url.normalized,
                verdict = verdict,
                scannedAt = scannedAt,
            ),
        )

        return result
    }

    private suspend fun checkReputation(url: ScannedUrl): ReputationResult {
        val result = withTimeoutOrNull(reputationTimeoutMillis) {
            try {
                reputationProvider.check(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        return result ?: ReputationResult(matched = false, source = reputationProvider.id, checked = false)
    }
}
