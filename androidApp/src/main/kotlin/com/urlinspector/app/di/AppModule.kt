package com.urlinspector.app.di

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.urlinspector.core.ReputationProvider
import com.urlinspector.core.ScanRepository
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.UrlExpander
import com.urlinspector.app.history.HistoryViewModel
import com.urlinspector.app.scan.ScanViewModel
import com.urlinspector.data.db.SqlDelightScanRepository
import com.urlinspector.data.db.UrlInspectorDatabase
import com.urlinspector.data.expansion.HttpUrlExpander
import com.urlinspector.data.reputation.ReputationCache
import com.urlinspector.data.reputation.SafeBrowsingClient
import com.urlinspector.data.reputation.SafeBrowsingReputationProvider
import com.urlinspector.data.reputation.safeBrowsingJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

// TODO(future milestone): real key provisioning (build config / secrets
// management) is out of scope for M3. An empty key means every reputation
// lookup fails (400/403), which ScanUrlUseCase.runGuarded already turns
// into a graceful heuristics-only fallback — this is intended, working
// DR2 behavior for now, not a bug.
private const val SAFE_BROWSING_API_KEY = ""

private const val REPUTATION_HTTP_CLIENT = "reputationHttpClient"
private const val EXPANDER_HTTP_CLIENT = "expanderHttpClient"
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

val appModule = module {
    single(named(REPUTATION_HTTP_CLIENT)) {
        HttpClient(CIO) {
            expectSuccess = true
            install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
            install(ContentNegotiation) { json(safeBrowsingJson) }
        }
    }

    single(named(EXPANDER_HTTP_CLIENT)) {
        HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
        }
    }

    single { ReputationCache() }

    single<ReputationProvider> {
        SafeBrowsingReputationProvider(
            client = SafeBrowsingClient(get(named(REPUTATION_HTTP_CLIENT)), apiKey = SAFE_BROWSING_API_KEY),
            cache = get(),
        )
    }

    single<UrlExpander> { HttpUrlExpander(get(named(EXPANDER_HTTP_CLIENT))) }

    single {
        val context: Context = get()
        val driver = AndroidSqliteDriver(UrlInspectorDatabase.Schema, context, "url_inspector.db")
        UrlInspectorDatabase(driver)
    }

    single<ScanRepository> { SqlDelightScanRepository(get()) }

    single { ScanUrlUseCase(get(), get(), get()) }

    viewModel { ScanViewModel(get()) }
    viewModel { HistoryViewModel(get()) }
}
