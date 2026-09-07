package com.urlinspector.app.sms

import com.urlinspector.app.fakes.FakeReputationProvider
import com.urlinspector.app.fakes.FakeScanRepository
import com.urlinspector.app.fakes.FakeUrlExpander
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeScanNotifier : ScanNotifier {
    data class Notification(val url: String, val verdict: Verdict)
    val notifications = mutableListOf<Notification>()

    override fun notify(url: String, verdict: Verdict) {
        notifications.add(Notification(url, verdict))
    }
}

class SmsScanCoordinatorTest {

    private fun coordinator(notifier: FakeScanNotifier): SmsScanCoordinator {
        val scanUrlUseCase = ScanUrlUseCase(
            reputationProvider = FakeReputationProvider(),
            urlExpander = FakeUrlExpander(),
            scanRepository = FakeScanRepository(),
        )
        return SmsScanCoordinator(scanUrlUseCase, notifier)
    }

    @Test
    fun `scans and notifies for a single URL in the message`() = runTest {
        val notifier = FakeScanNotifier()
        coordinator(notifier).processMessageBody("Your package: https://example.com/track")

        assertEquals(1, notifier.notifications.size)
        assertEquals("https://example.com/track", notifier.notifications[0].url)
        assertEquals(Verdict.SAFE, notifier.notifications[0].verdict)
    }

    @Test
    fun `scans and notifies for each URL when multiple are present`() = runTest {
        val notifier = FakeScanNotifier()
        coordinator(notifier).processMessageBody("https://a.example.com and https://b.example.com")

        assertEquals(2, notifier.notifications.size)
    }

    @Test
    fun `does nothing for a message with no URL`() = runTest {
        val notifier = FakeScanNotifier()
        coordinator(notifier).processMessageBody("Your OTP is 123456")

        assertEquals(0, notifier.notifications.size)
    }

    @Test
    fun `does not crash and does not notify when the extracted candidate is not a valid URL`() = runTest {
        val notifier = FakeScanNotifier()
        // "https://" extracts as a URL-shaped substring but has no host,
        // so core.UrlNormalizer rejects it — ScanUrlUseCase throws
        // InvalidUrlException, which the coordinator must swallow.
        coordinator(notifier).processMessageBody("weird link: https://")

        assertEquals(0, notifier.notifications.size)
    }
}
