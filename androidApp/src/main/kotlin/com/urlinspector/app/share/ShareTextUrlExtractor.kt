package com.urlinspector.app.share

private val URL_REGEX = Regex("""https?://\S+""")
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '}', '!', '?', ';', ':', '\'', '"')

/**
 * Finds every http(s) URL substring in arbitrary text (a shared payload,
 * or an SMS message body). Does not validate the URLs — that is
 * core.UrlNormalizer's job once a candidate reaches ScanUrlUseCase.
 */
fun extractUrls(text: String): List<String> =
    URL_REGEX.findAll(text)
        .map { it.value.trimEnd(*TRAILING_PUNCTUATION) }
        .toList()

/**
 * Finds the first http(s) URL substring in arbitrary shared text (e.g. a
 * WhatsApp/Messages share payload like "Check this out: https://...").
 * Returns null if no URL substring is present.
 */
fun extractFirstUrl(text: String): String? = extractUrls(text).firstOrNull()
