package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object IpLiteralHeuristic {
    private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        val host = url.host.removePrefix("[").removeSuffix("]")
        val isIpv4 = ipv4Regex.matches(host)
        val isIpv6 = host.count { it == ':' } >= 2

        return if (isIpv4 || isIpv6) {
            HeuristicFinding(
                id = "ip_literal",
                description = "The link uses a raw IP address ($host) instead of a domain name.",
            )
        } else {
            null
        }
    }
}
