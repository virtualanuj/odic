package com.urlinspector.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.model.Verdict
import com.urlinspector.data.db.SqlDelightScanRepository
import com.urlinspector.data.db.UrlInspectorDatabase
import com.urlinspector.data.expansion.HttpUrlExpander
import com.urlinspector.data.reputation.ReputationCache
import com.urlinspector.data.reputation.SafeBrowsingClient
import com.urlinspector.data.reputation.SafeBrowsingReputationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanUrlUseCaseIntegrationTest {

    @Test
    fun `a known-malicious URL is scanned end-to-end and persisted to real storage`() = runTest {
        val reputationEngine = MockEngine(
            MockEngineConfig().apply {
                addHandler { _ ->
                    respond(
                        content = ByteReadChannel("""{"matches":[{"threatType":"SOCIAL_ENGINEERING"}]}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                dispatcher = Dispatchers.Unconfined
            },
        )
        val reputationHttpClient = HttpClient(reputationEngine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        val reputationProvider = SafeBrowsingReputationProvider(
            SafeBrowsingClient(reputationHttpClient, apiKey = "test-key"),
            ReputationCache(),
        )

        val expanderEngine = MockEngine(
            MockEngineConfig().apply {
                addHandler { _ -> respond(content = ByteReadChannel.Empty, status = HttpStatusCode.OK) }
                dispatcher = Dispatchers.Unconfined
            },
        )
        val expanderHttpClient = HttpClient(expanderEngine) { followRedirects = false }
        val urlExpander = HttpUrlExpander(expanderHttpClient)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UrlInspectorDatabase.Schema.create(driver)
        val repository = SqlDelightScanRepository(UrlInspectorDatabase(driver))

        val useCase = ScanUrlUseCase(reputationProvider, urlExpander, repository)

        val result = useCase.scan("http://phishing.example.com/login")

        assertEquals(Verdict.MALICIOUS, result.verdict)

        val history = repository.getAll()
        assertEquals(1, history.size)
        assertEquals(Verdict.MALICIOUS, history.first().verdict)
    }
}
