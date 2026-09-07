package com.urlinspector.app.sms

import com.urlinspector.app.share.extractUrls
import com.urlinspector.core.InvalidUrlException
import com.urlinspector.core.ScanUrlUseCase
import kotlinx.coroutines.CancellationException

private const val MAX_URLS_PER_MESSAGE = 5

class SmsScanCoordinator(
    private val scanUrlUseCase: ScanUrlUseCase,
    private val notifier: ScanNotifier,
) {
    suspend fun processMessageBody(body: String) {
        for (url in extractUrls(body).take(MAX_URLS_PER_MESSAGE)) {
            try {
                val result = scanUrlUseCase.scan(url)
                notifier.notify(result.finalUrl.normalized, result.verdict)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidUrlException) {
                // The extracted candidate looked URL-shaped but wasn't
                // actually valid (e.g. no host) — skip it silently rather
                // than notifying about garbage.
            }
        }
    }
}
