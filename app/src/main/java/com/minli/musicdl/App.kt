package com.minli.musicdl

import android.app.Application
import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.minli.musicdl.data.DownloadRepository
import com.minli.musicdl.data.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    companion object {
        lateinit var settings: Settings
            private set
        lateinit var repository: DownloadRepository
            private set

        fun init(context: Context) {
            if (::settings.isInitialized) return
            synchronized(this) {
                if (::settings.isInitialized) return
                settings = Settings(context.applicationContext)
                repository = DownloadRepository(context.applicationContext, settings)
            }
        }
    }
}
