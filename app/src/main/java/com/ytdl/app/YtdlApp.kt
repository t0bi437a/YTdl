package com.ytdl.app

import android.app.Application
import com.ytdl.app.download.DownloadStore
import com.ytdl.app.download.Notifications
import com.ytdl.app.settings.SettingsRepository

class YtdlApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        DownloadStore.init(this)
        Notifications.createChannels(this)
    }
}
