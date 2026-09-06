package com.urlinspector.app

import android.app.Application
import com.urlinspector.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class UrlInspectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@UrlInspectorApp)
            modules(appModule)
        }
    }
}
