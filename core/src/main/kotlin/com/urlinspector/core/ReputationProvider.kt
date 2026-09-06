package com.urlinspector.core

import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScannedUrl

interface ReputationProvider {
    val id: String
    suspend fun check(url: ScannedUrl): ReputationResult
}
