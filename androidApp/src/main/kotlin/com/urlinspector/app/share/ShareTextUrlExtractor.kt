package com.urlinspector.app.share

private val URL_REGEX = Regex("""https?://\S+""")
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '}', '!', '?', ';', ':', '\'', '"')

/**
 * Finds the first http(s) URL substring in arbitrary shared text (e.g. a
 * WhatsApp/Messages share payload like "Check this out: https://...").
 * Returns null if no URL substring is present. Does not validate the URL —
 * that is core.UrlNormalizer's job once the extracted string reaches
 * ScanUrlUseCase.
 */
fun extractFirstUrl(text: String): String? {
    val match = URL_REGEX.find(text) ?: return null
    return match.value.trimEnd(*TRAILING_PUNCTUATION).ifBlank { null }
}
