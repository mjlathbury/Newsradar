package com.newsradar.app

import android.app.Application
import com.newsradar.app.util.Notifier
import com.newsradar.app.work.FetchScheduler

class NewsRadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        Notifier.ensureChannel(this)
        FetchScheduler.schedule(this, hour = 7)
    }
}
