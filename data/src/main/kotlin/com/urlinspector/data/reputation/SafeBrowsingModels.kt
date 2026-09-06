package com.urlinspector.data.reputation

import kotlinx.serialization.Serializable

@Serializable
data class SafeBrowsingClientInfo(
    val clientId: String,
    val clientVersion: String,
)

@Serializable
data class SafeBrowsingThreatEntry(
    val url: String,
)

@Serializable
data class SafeBrowsingThreatInfo(
    val threatTypes: List<String>,
    val platformTypes: List<String>,
    val threatEntryTypes: List<String>,
    val threatEntries: List<SafeBrowsingThreatEntry>,
)

@Serializable
data class SafeBrowsingFindRequest(
    val client: SafeBrowsingClientInfo,
    val threatInfo: SafeBrowsingThreatInfo,
)

@Serializable
data class SafeBrowsingThreatMatch(
    val threatType: String,
)

@Serializable
data class SafeBrowsingFindResponse(
    val matches: List<SafeBrowsingThreatMatch>? = null,
)
