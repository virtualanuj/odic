package com.urlinspector.core

import com.urlinspector.core.model.ScannedUrl

interface UrlExpander {
    suspend fun expand(url: ScannedUrl): ScannedUrl
}
