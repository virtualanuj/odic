package com.urlinspector.core

import com.urlinspector.core.fakes.FakeReputationProvider
import com.urlinspector.core.fakes.FakeScanRepository
import com.urlinspector.core.fakes.FakeUrlExpander
import com.urlinspector.core.fakes.ThrowingReputationProvider
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScannedUrl
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanUrlUseCaseTest {
    private val fixedNow = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `reputation match produces MALICIOUS verdict`() = runTest {
        val reputation = FakeReputationProvider(result = {
            ReputationResult(matched = true, source = "fake-reputation", checked = true)
        })
        val repository = FakeScanRepository()
        val useCase = ScanUrlUseCase(reputation, FakeUrlExpander(), repository, now = { fixedNow })

        val result = useCase.scan("http://example.com")

        assertEquals(Verdict.MALICIOUS, result.verdict)
        assertEquals(1, repository.saved.size)
        assertEquals(Verdict.MALICIOUS, repository.saved.first().verdict)
    }

    @Test
    fun `heuristic finding without reputation match produces SUSPICIOUS verdict`() = runTest {
        val reputation = FakeReputationProvider()
        val useCase = ScanUrlUseCase(reputation, FakeUrlExpander(), FakeScanRepository(), now = { fixedNow })

        val result = useCase.scan("http://203.0.113.5/login")

        assertEquals(Verdict.SUSPICIOUS, result.verdict)
        assertTrue(result.heuristicFindings.any { it.id == "ip_literal" })
    }

    @Test
    fun `no findings and no reputation match produces SAFE verdict`() = runTest {
        val reputation = FakeReputationProvider()
        val useCase = ScanUrlUseCase(reputation, FakeUrlExpander(), FakeScanRepository(), now = { fixedNow })

        val result = useCase.scan("http://example.com")

        assertEquals(Verdict.SAFE, result.verdict)
        assertTrue(result.heuristicFindings.isEmpty())
    }

    @Test
    fun `reputation failure falls back to heuristics-only verdict`() = runTest {
        val useCase = ScanUrlUseCase(
            ThrowingReputationProvider(),
            FakeUrlExpander(),
            FakeScanRepository(),
            now = { fixedNow },
        )

        val result = useCase.scan("http://example.com")

        assertEquals(Verdict.SAFE, result.verdict)
        assertFalse(result.reputationResult.checked)
    }

    @Test
    fun `shortener expansion re-checks the expanded destination`() = runTest {
        val shortUrl = requireNotNull(normalizeUrl("http://bit.ly/abc123"))
        val expandedUrl = requireNotNull(normalizeUrl("http://paypa1.com/login"))
        val expander = FakeUrlExpander(expansions = mapOf(shortUrl.normalized to expandedUrl))
        val reputation = FakeReputationProvider()
        val useCase = ScanUrlUseCase(reputation, expander, FakeScanRepository(), now = { fixedNow })

        val result = useCase.scan("http://bit.ly/abc123")

        assertEquals(Verdict.SUSPICIOUS, result.verdict)
        assertTrue(result.heuristicFindings.any { it.id == "shortener" })
        assertTrue(result.heuristicFindings.any { it.id == "typosquat" })
    }

    @Test
    fun `shortener expansion also re-checks the expanded destination for IP-literal hosts`() = runTest {
        val shortUrl = requireNotNull(normalizeUrl("http://bit.ly/xyz789"))
        val expandedUrl = requireNotNull(normalizeUrl("http://203.0.113.9/wallet"))
        val expander = FakeUrlExpander(expansions = mapOf(shortUrl.normalized to expandedUrl))
        val reputation = FakeReputationProvider()
        val useCase = ScanUrlUseCase(reputation, expander, FakeScanRepository(), now = { fixedNow })

        val result = useCase.scan("http://bit.ly/xyz789")

        assertEquals(Verdict.SUSPICIOUS, result.verdict)
        assertTrue(result.heuristicFindings.any { it.id == "shortener" })
        assertTrue(result.heuristicFindings.any { it.id == "ip_literal" })
    }

    @Test
    fun `Cyrillic lookalike host is flagged via the real pipeline, not just the heuristic unit test`() = runTest {
        val reputation = FakeReputationProvider()
        val useCase = ScanUrlUseCase(reputation, FakeUrlExpander(), FakeScanRepository(), now = { fixedNow })

        val result = useCase.scan("http://\u0430pple.com")

        assertEquals(Verdict.SUSPICIOUS, result.verdict)
        assertTrue(result.heuristicFindings.any { it.id == "homograph" })
    }

    @Test
    fun `repository save failure does not prevent scan from returning a result`() = runTest {
        val failingRepository = object : ScanRepository {
            override suspend fun save(entry: com.urlinspector.core.model.ScanHistoryEntry) {
                throw RuntimeException("simulated database failure")
            }
            override suspend fun getAll() = emptyList<com.urlinspector.core.model.ScanHistoryEntry>()
            override suspend fun deleteById(id: String) {}
            override suspend fun clearAll() {}
        }
        val useCase = ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), failingRepository, now = { fixedNow })

        val result = useCase.scan("http://example.com")

        assertEquals(Verdict.SAFE, result.verdict)
    }

    @Test
    fun `reputation lookup that exceeds the timeout falls back to heuristics-only verdict`() = runTest {
        val slowReputationProvider = object : ReputationProvider {
            override val id = "slow-reputation"
            override suspend fun check(url: ScannedUrl): ReputationResult {
                delay(10_000)
                return ReputationResult(matched = true, source = id, checked = true)
            }
        }
        val useCase = ScanUrlUseCase(
            slowReputationProvider,
            FakeUrlExpander(),
            FakeScanRepository(),
            reputationTimeoutMillis = 100L,
            now = { fixedNow },
        )

        val result = useCase.scan("http://example.com")

        assertEquals(Verdict.SAFE, result.verdict)
        assertFalse(result.reputationResult.checked)
    }

    @Test
    fun `invalid input raises InvalidUrlException`() = runTest {
        val useCase = ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository(), now = { fixedNow })

        assertFailsWith<InvalidUrlException> {
            useCase.scan("   ")
        }
    }
}
