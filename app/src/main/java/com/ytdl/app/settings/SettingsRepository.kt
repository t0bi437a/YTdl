package com.ytdl.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** `-1` means "always ask", `0` means "best available". */
object Quality {
    const val ASK = -1
    const val BEST = 0
    val CHOICES = listOf(ASK, BEST, 2160, 1440, 1080, 720, 480, 360, 240, 144)
}

data class Settings(
    val defaultHeight: Int = Quality.ASK,
    val defaultAudioBitrate: Int = 0,
    val preferWebm: Boolean = false,
    val audioOnlyByDefault: Boolean = false,
    val wifiOnly: Boolean = false,
    val maxParallel: Int = 2,
    val folderUri: String? = null,
    val filenameTemplate: String = "%title% [%quality%]",
    val downloadSubtitles: Boolean = false,
    val saveThumbnail: Boolean = false,
    val instantShareDownload: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val height = intPreferencesKey("default_height")
        val audioBitrate = intPreferencesKey("default_audio_bitrate")
        val preferWebm = booleanPreferencesKey("prefer_webm")
        val audioOnly = booleanPreferencesKey("audio_only_default")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val maxParallel = intPreferencesKey("max_parallel")
        val folderUri = stringPreferencesKey("folder_uri")
        val filename = stringPreferencesKey("filename_template")
        val subtitles = booleanPreferencesKey("download_subtitles")
        val thumbnail = booleanPreferencesKey("save_thumbnail")
        val instantShare = booleanPreferencesKey("instant_share")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        val defaults = Settings()
        Settings(
            defaultHeight = p[Keys.height] ?: defaults.defaultHeight,
            defaultAudioBitrate = p[Keys.audioBitrate] ?: defaults.defaultAudioBitrate,
            preferWebm = p[Keys.preferWebm] ?: defaults.preferWebm,
            audioOnlyByDefault = p[Keys.audioOnly] ?: defaults.audioOnlyByDefault,
            wifiOnly = p[Keys.wifiOnly] ?: defaults.wifiOnly,
            maxParallel = p[Keys.maxParallel] ?: defaults.maxParallel,
            folderUri = p[Keys.folderUri],
            filenameTemplate = p[Keys.filename] ?: defaults.filenameTemplate,
            downloadSubtitles = p[Keys.subtitles] ?: defaults.downloadSubtitles,
            saveThumbnail = p[Keys.thumbnail] ?: defaults.saveThumbnail,
            instantShareDownload = p[Keys.instantShare] ?: defaults.instantShareDownload,
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.theme] ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.dynamic] ?: defaults.dynamicColor,
        )
    }

    suspend fun setDefaultHeight(v: Int) = edit { it[Keys.height] = v }
    suspend fun setDefaultAudioBitrate(v: Int) = edit { it[Keys.audioBitrate] = v }
    suspend fun setPreferWebm(v: Boolean) = edit { it[Keys.preferWebm] = v }
    suspend fun setAudioOnly(v: Boolean) = edit { it[Keys.audioOnly] = v }
    suspend fun setWifiOnly(v: Boolean) = edit { it[Keys.wifiOnly] = v }
    suspend fun setMaxParallel(v: Int) = edit { it[Keys.maxParallel] = v.coerceIn(1, 5) }
    suspend fun setFilenameTemplate(v: String) = edit { it[Keys.filename] = v }
    suspend fun setDownloadSubtitles(v: Boolean) = edit { it[Keys.subtitles] = v }
    suspend fun setSaveThumbnail(v: Boolean) = edit { it[Keys.thumbnail] = v }
    suspend fun setInstantShare(v: Boolean) = edit { it[Keys.instantShare] = v }
    suspend fun setThemeMode(v: ThemeMode) = edit { it[Keys.theme] = v.name }
    suspend fun setDynamicColor(v: Boolean) = edit { it[Keys.dynamic] = v }

    suspend fun setFolderUri(v: String?) = edit {
        if (v == null) it.remove(Keys.folderUri) else it[Keys.folderUri] = v
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
