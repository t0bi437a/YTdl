package com.ytdl.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ytdl.app.download.DownloadPlanner
import com.ytdl.app.download.DownloadService
import com.ytdl.app.settings.SettingsRepository
import com.ytdl.app.ui.MainViewModel
import com.ytdl.app.ui.QualitySheet
import com.ytdl.app.ui.theme.YTdlTheme
import com.ytdl.app.youtube.UrlUtils
import com.ytdl.app.youtube.YoutubeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Entry point for "Share -> YTdl" from the YouTube app, and for opening a
 * youtube.com link with YTdl. Shows only the quality sheet on top of whatever
 * the user was doing, then gets out of the way.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoId = resolveVideoId(intent)
        if (videoId == null) {
            Toast.makeText(this, R.string.err_no_link, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val settings = SettingsRepository(applicationContext).flow.first()
            if (settings.instantShareDownload) {
                downloadWithDefaults(videoId)
            } else {
                showPicker(videoId)
            }
        }
    }

    /** Handles both ACTION_SEND (shared text) and ACTION_VIEW (a tapped link). */
    private fun resolveVideoId(intent: Intent?): String? {
        if (intent == null) return null

        val candidates = listOfNotNull(
            intent.dataString,
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        )
        for (candidate in candidates) {
            UrlUtils.extractVideoId(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun downloadWithDefaults(videoId: String) {
        val settings = SettingsRepository(applicationContext).flow.first()
        try {
            val info = YoutubeRepository.getStreams(videoId)
            val audioOnly = settings.audioOnlyByDefault
            val video = if (audioOnly) null else DownloadPlanner.autoSelectVideo(info, settings)
            val audio = DownloadPlanner.autoSelectAudio(info, settings)

            if (!audioOnly && video == null) {
                Toast.makeText(this, R.string.err_no_streams, Toast.LENGTH_LONG).show()
            } else {
                val task = DownloadPlanner.build(info, video, audio, settings)
                DownloadService.enqueue(this, task)
                Toast.makeText(this, R.string.added_to_queue, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            Toast.makeText(this, e.message ?: getString(R.string.failed), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun showPicker(videoId: String) {
        setContent {
            val model: MainViewModel = viewModel()
            val settings by model.settings.collectAsState()
            val picker by model.picker.collectAsState()

            LaunchedEffect(videoId) {
                model.openPicker(
                    videoId = videoId,
                    title = "",
                    author = "",
                    thumbnailUrl = UrlUtils.thumbnailUrl(videoId),
                )
            }

            YTdlTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                picker?.let { state ->
                    QualitySheet(
                        state = state,
                        preferWebm = settings.preferWebm,
                        onDismiss = { finish() },
                        onDownload = { info, video, audio ->
                            model.startDownload(info, video, audio)
                            Toast.makeText(
                                this@ShareActivity,
                                R.string.added_to_queue,
                                Toast.LENGTH_SHORT,
                            ).show()
                            finish()
                        },
                    )
                }
            }
        }
    }
}
