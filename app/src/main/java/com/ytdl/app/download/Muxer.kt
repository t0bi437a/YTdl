package com.ytdl.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Combines a video-only and an audio-only file into a single container without
 * re-encoding, using the platform muxer. No FFmpeg, no native code.
 */
object Muxer {

    private const val TAG = "Muxer"
    private const val DEFAULT_BUFFER = 1 shl 20

    class MuxException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @return the container actually written ("mp4" or "webm").
     * MP4 is tried first because it plays everywhere; WebM is the fallback for
     * VP9+Opus combinations the MP4 muxer refuses.
     */
    fun mux(videoFile: File, audioFile: File, output: File, preferWebm: Boolean): String {
        val order = if (preferWebm) listOf("webm", "mp4") else listOf("mp4", "webm")
        var last: Throwable? = null
        for (container in order) {
            try {
                muxInto(videoFile, audioFile, output, container)
                return container
            } catch (e: Throwable) {
                Log.w(TAG, "muxing into $container failed", e)
                last = e
                output.delete()
            }
        }
        throw MuxException("Could not merge video and audio", last)
    }

    private fun muxInto(videoFile: File, audioFile: File, output: File, container: String) {
        val format = if (container == "webm") {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        } else {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = selectTrack(videoExtractor, "video/")
                ?: throw MuxException("No video track found")
            val audioTrack = selectTrack(audioExtractor, "audio/")
                ?: throw MuxException("No audio track found")

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            muxer = MediaMuxer(output.absolutePath, format)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()
            started = true

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            copy(videoExtractor, muxer, outVideo, bufferSize(videoFormat))
            copy(audioExtractor, muxer, outAudio, bufferSize(audioFormat))
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun bufferSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 8 shl 20)
        } else DEFAULT_BUFFER

    private fun copy(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        bufferSize: Int,
    ) {
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            val time = extractor.sampleTime
            if (time < 0) break

            info.offset = 0
            info.size = size
            info.presentationTimeUs = time
            info.flags = extractor.sampleFlags and
                (MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            muxer.writeSampleData(trackIndex, buffer, info)
            if (!extractor.advance()) break
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        return null
    }
}
