package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object TyposquatHeuristic {
    private val protectedDomains = setOf(
        "paypal.com", "amazon.com", "apple.com", "google.com", "microsoft.com",
        "netflix.com", "facebook.com", "instagram.com", "whatsapp.com", "bankofamerica.com",
    )

    private const val MAX_SUSPICIOUS_DISTANCE = 2

    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        val host = url.host
        if (host in protectedDomains) return null

        val closest = protectedDomains
            .map { it to levenshteinDistance(host, it) }
            .filter { (_, distance) -> distance in 1..MAX_SUSPICIOUS_DISTANCE }
            .minByOrNull { (_, distance) -> distance }

        return closest?.let { (brand, _) ->
            HeuristicFinding(
                id = "typosquat",
                description = "The domain \"$host\" closely resembles \"$brand\" and may be impersonating it.",
            )
        }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[a.length][b.length]
    }
}
