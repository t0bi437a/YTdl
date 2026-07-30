package com.ytdl.app

import android.app.Application
import com.ytdl.app.download.DownloadStore
import com.ytdl.app.download.Notifications
import com.ytdl.app.download.YtDlp
import com.ytdl.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YtdlApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        DownloadStore.init(this)
        Notifications.createChannels(this)

        // Warm up yt-dlp so the first download doesn't pay the init cost, and
        // refresh its binary in the background to keep up with YouTube changes.
        scope.launch {
            runCatching {
                YtDlp.ensureInit(this@YtdlApp)
                YtDlp.tryUpdate(this@YtdlApp)
            }
        }
    }
}
