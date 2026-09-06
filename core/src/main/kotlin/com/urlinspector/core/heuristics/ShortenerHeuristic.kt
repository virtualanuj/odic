package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object ShortenerHeuristic {
    private val shortenerDomains = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly",
        "is.gd", "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at",
    )

    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        return if (url.host in shortenerDomains) {
            HeuristicFinding(
                id = "shortener",
                description = "The link uses a URL shortener (${url.host}), which hides the real destination.",
            )
        } else {
            null
        }
    }
}
