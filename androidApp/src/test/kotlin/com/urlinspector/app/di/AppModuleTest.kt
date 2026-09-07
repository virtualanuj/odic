package com.urlinspector.app.di

import android.content.Context
import android.content.SharedPreferences
import com.urlinspector.app.history.HistoryViewModel
import com.urlinspector.app.scan.ScanViewModel
import com.urlinspector.data.db.UrlInspectorDatabase
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.check.checkKoinModules
import org.koin.test.mock.MockProvider
import org.mockito.Mockito
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * JVM guard for the Koin graph — catches wiring bugs that would otherwise only
 * surface as a crash on a device.
 *
 * Two complementary checks:
 *  1. `checkKoinModules` instantiates every declared binding and verifies its
 *     dependency graph resolves.
 *  2. Every ViewModel the Compose UI resolves with `koinViewModel()` is
 *     actually resolvable. A definition that is simply *missing* (the real bug
 *     found on device: `ScanViewModel` was never bound) is invisible to check
 *     1, because that check only inspects definitions that already exist.
 *
 * Android platform pieces — `Context`, and the SQLDelight database (it wraps an
 * Android SQLite driver) — are supplied as mocks.
 */
class AppModuleTest {

    private val androidOverrides = module {
        single<Context> {
            val mockSharedPrefs = Mockito.mock(SharedPreferences::class.java)
            Mockito.`when`(mockSharedPrefs.getBoolean("sms_scanning_enabled", false)).thenReturn(false)
            val mockContext = Mockito.mock(Context::class.java)
            Mockito.`when`(mockContext.getSharedPreferences("url_inspector_settings", Context.MODE_PRIVATE))
                .thenReturn(mockSharedPrefs)
            mockContext
        }
        single { Mockito.mock(UrlInspectorDatabase::class.java) }
    }

    private val app = koinApplication {
        allowOverride(true)
        modules(appModule, androidOverrides)
    }

    @AfterTest
    fun tearDown() {
        app.close()
    }

    // checkKoinModules is deprecated in favour of Module.verify(), but verify()
    // is purely reflective: it inspects the *constructor* of each definition's
    // type, so it reports a false "missing HttpClientEngine" for the Ktor
    // HttpClient singletons, which build their own CIO engine inside the
    // definition lambda. checkKoinModules actually instantiates the graph and
    // is the stronger check here.
    @Suppress("DEPRECATION")
    @Test
    fun `appModule resolves every declared binding`() {
        MockProvider.register { clazz -> Mockito.mock(clazz.java) }
        checkKoinModules(listOf(appModule)) {
            val mockSharedPrefs = Mockito.mock(SharedPreferences::class.java)
            Mockito.`when`(mockSharedPrefs.getBoolean("sms_scanning_enabled", false)).thenReturn(false)
            val mockContext = Mockito.mock(Context::class.java)
            Mockito.`when`(mockContext.getSharedPreferences("url_inspector_settings", Context.MODE_PRIVATE))
                .thenReturn(mockSharedPrefs)
            withInstance<Context>(mockContext)
            withInstance<UrlInspectorDatabase>(Mockito.mock(UrlInspectorDatabase::class.java))
        }
    }

    @Test
    fun `appModule binds every view model the UI injects`() {
        app.koin.get<ScanViewModel>()
        app.koin.get<HistoryViewModel>()
    }
}
