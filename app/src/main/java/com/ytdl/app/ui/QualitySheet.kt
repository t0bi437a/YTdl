package com.ytdl.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ytdl.app.R
import com.ytdl.app.youtube.MediaStream
import com.ytdl.app.youtube.StreamInfo
import com.ytdl.app.youtube.StreamKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    state: PickerState,
    preferWebm: Boolean,
    onDismiss: () -> Unit,
    onDownload: (StreamInfo, MediaStream?, MediaStream?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(
                    url = state.thumbnailUrl,
                    modifier = Modifier
                        .width(110.dp)
                        .aspectRatio(16f / 9f),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = state.title.ifEmpty { stringResource(R.string.choose_quality) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.author.isNotBlank()) {
                        Text(
                            text = state.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.size(16.dp))

            when {
                state.loading -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.error != null -> Column(Modifier.padding(vertical = 16.dp)) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
                }

                state.info != null -> StreamChooser(
                    info = state.info,
                    preferWebm = preferWebm,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamChooser(
    info: StreamInfo,
    preferWebm: Boolean,
    onDownload: (StreamInfo, MediaStream?, MediaStream?) -> Unit,
) {
    var audioOnly by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !audioOnly,
            onClick = { audioOnly = false },
            label = { Text(stringResource(R.string.video)) },
            leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
        )
        FilterChip(
            selected = audioOnly,
            onClick = { audioOnly = true },
            label = { Text(stringResource(R.string.audio_only)) },
            leadingIcon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
        )
    }

    Spacer(Modifier.size(12.dp))

    val options = if (audioOnly) info.audioOptions() else info.videoOptions(preferWebm)

    if (options.isEmpty()) {
        Text(
            stringResource(R.string.err_no_streams),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        return
    }

    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        items(options, key = { "${it.itag}" }) { stream ->
            StreamRow(
                stream = stream,
                durationSeconds = info.durationSeconds,
                needsAudioTrack = !audioOnly && stream.kind == StreamKind.VIDEO_ONLY,
                audioForMerge = info.bestAudio(preferWebm),
                onClick = {
                    if (audioOnly) {
                        onDownload(info, null, stream)
                    } else {
                        onDownload(info, stream, info.bestAudio(preferWebm))
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun StreamRow(
    stream: MediaStream,
    durationSeconds: Long,
    needsAudioTrack: Boolean,
    audioForMerge: MediaStream?,
    onClick: () -> Unit,
) {
    val extraBytes = if (needsAudioTrack) audioForMerge?.contentLength ?: 0L else 0L
    val known = stream.contentLength + extraBytes
    val size = if (known > 0) {
        Format.bytes(known)
    } else {
        val estimate = Format.estimate(
            stream.bitrate + (if (needsAudioTrack) audioForMerge?.bitrate ?: 0L else 0L),
            durationSeconds,
        )
        if (estimate > 0) "≈ " + Format.bytes(estimate) else "—"
    }

    val details = buildList {
        add(stream.container.uppercase())
        if (stream.codec.isNotBlank()) add(stream.codec.substringBefore('.'))
        if (stream.fps > 0) add("${stream.fps} fps")
        if (needsAudioTrack) add("+ audio")
    }.joinToString(" · ")

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stream.qualityLabel.ifEmpty { "itag ${stream.itag}" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = size,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
