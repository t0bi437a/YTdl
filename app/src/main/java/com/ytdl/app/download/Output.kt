package com.ytdl.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Moves a finished temp file to wherever the user asked for it: a folder picked
 * through SAF, or the shared Movies/YTdl and Music/YTdl collections.
 */
object Output {

    private const val SUBFOLDER = "YTdl"

    fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/opus"
        "vtt" -> "text/vtt"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    fun isAudio(extension: String): Boolean =
        extension.lowercase() in setOf("m4a", "opus", "mp3", "ogg", "aac")

    /**
     * @param source deleted once it has been copied to the destination.
     * @return the uri of the saved file.
     */
    fun publish(
        context: Context,
        source: File,
        displayName: String,
        extension: String,
        folderUri: String?,
    ): Uri {
        val fullName = "$displayName.$extension"
        val mime = mimeFor(extension)

        val uri = if (folderUri != null) {
            writeToTree(context, source, fullName, mime, folderUri)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToMediaStore(context, source, fullName, mime, extension)
        } else {
            writeToLegacyStorage(context, source, fullName, mime, extension)
        }

        source.delete()
        return uri
    }

    private fun writeToTree(
        context: Context,
        source: File,
        fullName: String,
        mime: String,
        folderUri: String,
    ): Uri {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            ?: throw IllegalStateException("Download folder is not available")
        if (!tree.canWrite()) throw IllegalStateException("No write access to the download folder")

        tree.findFile(fullName)?.delete()
        val target = tree.createFile(mime, fullName)
            ?: throw IllegalStateException("Could not create $fullName")

        context.contentResolver.openOutputStream(target.uri)?.use { out ->
            source.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE) }
        } ?: throw IllegalStateException("Could not open $fullName for writing")

        return target.uri
    }

    private fun writeToMediaStore(
        context: Context,
        source: File,
        fullName: String,
        mime: String,
        extension: String,
    ): Uri {
        val audio = isAudio(extension)
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relative = if (audio) {
            "${Environment.DIRECTORY_MUSIC}/$SUBFOLDER"
        } else {
            "${Environment.DIRECTORY_MOVIES}/$SUBFOLDER"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fullName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create a media entry for $fullName")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: throw IllegalStateException("Could not open $fullName for writing")
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyStorage(
        context: Context,
        source: File,
        fullName: String,
        mime: String,
        extension: String,
    ): Uri {
        val base = Environment.getExternalStoragePublicDirectory(
            if (isAudio(extension)) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        )
        val dir = File(base, SUBFOLDER).apply { mkdirs() }
        val target = File(dir, fullName)
        source.inputStream().use { input ->
            target.outputStream().use { input.copyTo(it, DEFAULT_BUFFER_SIZE) }
        }

        // Make the file visible to the gallery / music apps.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, target.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, fullName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        val collection = if (isAudio(extension)) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
            ?: Uri.fromFile(target)
    }

    /** Windows/FAT-safe file name. */
    fun sanitize(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        val safe = cleaned.ifEmpty { "video" }
        return if (safe.length > 120) safe.substring(0, 120).trim() else safe
    }
}
