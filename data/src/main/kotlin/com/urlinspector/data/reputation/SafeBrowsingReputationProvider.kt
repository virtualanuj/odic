package com.urlinspector.data.reputation

import com.urlinspector.core.ReputationProvider
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScannedUrl
import java.time.Instant

class SafeBrowsingReputationProvider(
    private val client: SafeBrowsingClient,
    private val cache: ReputationCache,
    override val id: String = "safe-browsing",
    private val now: () -> Instant = Instant::now,
) : ReputationProvider {

    override suspend fun check(url: ScannedUrl): ReputationResult {
        val cacheKey = url.normalized
        val currentTime = now()

        cache.get(cacheKey, currentTime)?.let { return it }

        val matches = client.findThreatMatches(url.normalized)
        val result = ReputationResult(matched = matches.isNotEmpty(), source = id, checked = true)

        cache.put(cacheKey, result, currentTime)
        return result
    }
}
