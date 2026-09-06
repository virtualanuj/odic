package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object SuspiciousTldHeuristic {
    private val suspiciousTlds = setOf(
        "zip", "mov", "tk", "ml", "ga", "cf", "gq", "top", "xyz",
        "work", "click", "link", "country", "stream", "gdn", "kim",
        "loan", "men", "party", "review", "science", "trade", "win",
    )

    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        val tld = url.host.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (tld.isNotEmpty() && tld in suspiciousTlds) {
            HeuristicFinding(
                id = "suspicious_tld",
                description = "The domain uses a top-level domain (.$tld) that is frequently abused for scams.",
            )
        } else {
            null
        }
    }
}
