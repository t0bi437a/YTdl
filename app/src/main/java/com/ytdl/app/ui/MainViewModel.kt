package com.ytdl.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytdl.app.R
import com.ytdl.app.download.DownloadPlanner
import com.ytdl.app.download.DownloadService
import com.ytdl.app.download.DownloadStatus
import com.ytdl.app.download.DownloadStore
import com.ytdl.app.settings.Settings
import com.ytdl.app.settings.SettingsRepository
import com.ytdl.app.youtube.ChannelItem
import com.ytdl.app.youtube.ExtractionException
import com.ytdl.app.youtube.MediaStream
import com.ytdl.app.youtube.StreamInfo
import com.ytdl.app.youtube.StreamKind
import com.ytdl.app.youtube.UrlUtils
import com.ytdl.app.youtube.VideoItem
import com.ytdl.app.youtube.YoutubeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

enum class SearchTab { VIDEOS, CHANNELS }

data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.VIDEOS,
    val videos: List<VideoItem> = emptyList(),
    val channels: List<ChannelItem> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    /** Non-null while browsing a single channel's uploads. */
    val channelContext: ChannelItem? = null,
)

/** Progress of a "download all selected" run. */
data class BatchState(
    val total: Int,
    val done: Int = 0,
    val failed: Int = 0,
    val running: Boolean = true,
)

/** Bottom-sheet state for picking a quality. */
data class PickerState(
    val videoId: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val loading: Boolean = true,
    val info: StreamInfo? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)

    val settings: StateFlow<Settings> = settingsRepo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val downloads = DownloadStore.tasks

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _picker = MutableStateFlow<PickerState?>(null)
    val picker: StateFlow<PickerState?> = _picker.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    /** Ids of videos ticked in the search list; empty means selection mode is off. */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()

    private var searchJob: Job? = null

    fun settingsRepository(): SettingsRepository = settingsRepo

    fun consumeMessage() {
        _messages.value = null
    }

    fun onQueryChange(value: String) {
        _search.value = _search.value.copy(query = value)
    }

    fun onTabChange(tab: SearchTab) {
        val current = _search.value
        if (current.tab == tab) return
        _search.value = current.copy(tab = tab, channelContext = null)
        if (current.query.isNotBlank()) submit(current.query)
    }

    /**
     * A link opens the quality picker straight away; anything else is treated as
     * a search phrase (title, channel name, or both).
     */
    fun submit(rawQuery: String? = null) {
        val query = (rawQuery ?: _search.value.query).trim()
        if (query.isEmpty()) return

        val videoId = UrlUtils.extractVideoId(query)
        if (videoId != null) {
            openPicker(videoId, title = "", author = "", thumbnailUrl = UrlUtils.thumbnailUrl(videoId))
            return
        }

        searchJob?.cancel()
        _search.value = _search.value.copy(
            query = query,
            loading = true,
            error = null,
            hasSearched = true,
            channelContext = null,
            videos = emptyList(),
            channels = emptyList(),
            continuation = null,
        )

        searchJob = viewModelScope.launch {
            try {
                val page = when (_search.value.tab) {
                    SearchTab.VIDEOS -> YoutubeRepository.searchVideos(query)
                    SearchTab.CHANNELS -> YoutubeRepository.searchChannels(query)
                }
                _search.value = _search.value.copy(
                    loading = false,
                    videos = page.videos,
                    channels = page.channels,
                    continuation = page.continuation,
                )
            } catch (e: Throwable) {
                _search.value = _search.value.copy(loading = false, error = describe(e))
            }
        }
    }

    fun loadMore() {
        val state = _search.value
        val token = state.continuation ?: return
        if (state.loadingMore || state.loading) return

        _search.value = state.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = if (state.channelContext != null) {
                    YoutubeRepository.continueBrowse(token)
                } else {
                    YoutubeRepository.continueSearch(token)
                }
                val current = _search.value
                _search.value = current.copy(
                    loadingMore = false,
                    videos = (current.videos + page.videos).distinctBy { it.id },
                    channels = (current.channels + page.channels).distinctBy { it.id },
                    continuation = page.continuation,
                )
            } catch (e: Throwable) {
                _search.value = _search.value.copy(loadingMore = false, error = describe(e))
            }
        }
    }

    fun openChannel(channel: ChannelItem) {
        searchJob?.cancel()
        _search.value = _search.value.copy(
            loading = true,
            error = null,
            hasSearched = true,
            videos = emptyList(),
            channels = emptyList(),
            continuation = null,
            channelContext = channel,
            tab = SearchTab.VIDEOS,
        )
        searchJob = viewModelScope.launch {
            try {
                val page = YoutubeRepository.channelVideos(channel.id)
                _search.value = _search.value.copy(
                    loading = false,
                    videos = page.videos,
                    continuation = page.continuation,
                )
            } catch (e: Throwable) {
                _search.value = _search.value.copy(loading = false, error = describe(e))
            }
        }
    }

    fun leaveChannel() {
        val state = _search.value
        _search.value = state.copy(channelContext = null)
        if (state.query.isNotBlank()) submit(state.query)
    }

    // ------------------------------------------------------------ quality picker

    fun openPicker(video: VideoItem) =
        openPicker(video.id, video.title, video.author, video.thumbnailUrl)

    fun openPicker(videoId: String, title: String, author: String, thumbnailUrl: String) {
        _picker.value = PickerState(videoId, title, author, thumbnailUrl, loading = true)
        viewModelScope.launch {
            try {
                val info = YoutubeRepository.getStreams(videoId)
                val current = _picker.value
                if (current?.videoId == videoId) {
                    _picker.value = current.copy(
                        loading = false,
                        info = info,
                        title = current.title.ifEmpty { info.title },
                        author = current.author.ifEmpty { info.author },
                        thumbnailUrl = current.thumbnailUrl.ifEmpty { info.thumbnailUrl },
                    )
                }
            } catch (e: Throwable) {
                // Metadata sources can all be down; the picker still works with a
                // generic quality list because yt-dlp resolves formats at download
                // time from the height alone.
                val current = _picker.value
                if (current?.videoId == videoId) {
                    _picker.value = current.copy(loading = false, info = genericStreams(videoId))
                }
            }
        }
    }

    private fun genericStreams(videoId: String): StreamInfo {
        val heights = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        val video = heights.map { h ->
            MediaStream(
                itag = -1, url = "", mimeType = "", container = "mp4", codec = "",
                kind = StreamKind.VIDEO_ONLY, qualityLabel = "${h}p", height = h,
                fps = 0, bitrate = 0, audioSampleRate = 0, contentLength = 0,
            )
        }
        val audio = listOf(
            MediaStream(
                itag = -1, url = "", mimeType = "", container = "mp4", codec = "",
                kind = StreamKind.AUDIO_ONLY, qualityLabel = "Audio", height = 0,
                fps = 0, bitrate = 0, audioSampleRate = 0, contentLength = 0,
            )
        )
        return StreamInfo(
            id = videoId, title = "", author = "", channelId = null, durationSeconds = 0,
            thumbnailUrl = UrlUtils.thumbnailUrl(videoId), viewCount = 0,
            videoStreams = video, audioStreams = audio, subtitles = emptyList(),
        )
    }

    fun closePicker() {
        _picker.value = null
    }

    fun startDownload(info: StreamInfo, videoStream: MediaStream?, audioStream: MediaStream?) {
        viewModelScope.launch {
            val config = settingsRepo.flow.first()
            val task = DownloadPlanner.build(info, videoStream, audioStream, config)
            DownloadService.enqueue(getApplication(), task)
            _picker.value = null
            _messages.value = getApplication<Application>().getString(R.string.download_started)
        }
    }

    // ------------------------------------------------------- batch / selection

    fun toggleSelection(videoId: String) {
        val current = _selection.value
        _selection.value = if (videoId in current) current - videoId else current + videoId
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAllVisible() {
        _selection.value = _search.value.videos.map { it.id }.toSet()
    }

    /**
     * Queues every selected video at one quality. No per-video resolving is
     * needed — yt-dlp does that at download time — so the whole batch is enqueued
     * instantly from the search metadata.
     *
     * @param height target video height, or 0 for best; ignored when [audioOnly].
     */
    fun downloadSelected(height: Int, audioOnly: Boolean) {
        val videos = _search.value.videos.filter { it.id in _selection.value }
        if (videos.isEmpty()) return

        _selection.value = emptySet()
        viewModelScope.launch {
            val config = settingsRepo.flow.first()
            val built = videos.map { video ->
                DownloadPlanner.build(
                    meta = DownloadPlanner.VideoMeta(
                        id = video.id,
                        title = video.title,
                        author = video.author,
                        thumbnailUrl = video.thumbnailUrl,
                        durationSeconds = video.durationSeconds,
                    ),
                    height = height,
                    audioOnly = audioOnly,
                    settings = config,
                )
            }
            DownloadStore.addAll(built)
            DownloadService.sync(getApplication())
            _batch.value = BatchState(built.size, built.size, 0, running = false)
            _messages.value = getApplication<Application>().getString(R.string.added_to_queue)
        }
    }

    fun dismissBatch() {
        _batch.value = null
    }

    // --------------------------------------------------------------- downloads

    fun moveInQueue(id: String, up: Boolean) = DownloadStore.move(id, up)

    fun pauseAll() {
        DownloadStore.snapshot()
            .filter { it.status == DownloadStatus.RUNNING }
            .forEach { DownloadService.pause(getApplication(), it.id) }
        DownloadStore.pauseAllWaiting()
    }

    fun resumeAll() {
        DownloadStore.resumeAllPaused()
        DownloadService.sync(getApplication())
    }

    fun cancelDownload(id: String) = DownloadService.cancel(getApplication(), id)
    fun pauseDownload(id: String) = DownloadService.pause(getApplication(), id)
    fun resumeDownload(id: String) {
        DownloadService.resume(getApplication(), id)
        DownloadService.sync(getApplication())
    }

    fun removeDownload(id: String) {
        DownloadService.cancel(getApplication(), id)
        DownloadStore.remove(id)
    }

    fun clearFinished() = DownloadStore.clearFinished()

    private fun describe(e: Throwable): String {
        val context = getApplication<Application>()
        return when (e) {
            is IOException -> context.getString(R.string.err_network)
            is ExtractionException -> e.message?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.err_unavailable, it) }
                ?: context.getString(R.string.err_no_streams)

            else -> e.message ?: e::class.java.simpleName
        }
    }
}
