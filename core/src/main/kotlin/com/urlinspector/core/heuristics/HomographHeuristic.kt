package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object HomographHeuristic {
    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        val host = url.host
        val hasPunycodeLabel = host.split('.').any { it.startsWith("xn--") }
        val hasNonAscii = host.any { it.code > 127 }

        return if (hasPunycodeLabel || hasNonAscii) {
            HeuristicFinding(
                id = "homograph",
                description = "The domain \"$host\" contains internationalized characters that can be used to visually impersonate another domain.",
            )
        } else {
            null
        }
    }
}
