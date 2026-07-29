package com.ytdl.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ytdl.app.R
import com.ytdl.app.youtube.ChannelItem
import com.ytdl.app.youtube.VideoItem

@Composable
fun HomeScreen(
    state: SearchUiState,
    selection: Set<String>,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onTabChange: (SearchTab) -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    onVideoLongClick: (VideoItem) -> Unit,
    onChannelClick: (ChannelItem) -> Unit,
    onLeaveChannel: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDownloadSelected: (height: Int, audioOnly: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showBatchDialog by remember { mutableStateOf(false) }

    if (showBatchDialog) {
        BatchQualityDialog(
            count = selection.size,
            onDismiss = { showBatchDialog = false },
            onConfirm = { height, audioOnly ->
                showBatchDialog = false
                onDownloadSelected(height, audioOnly)
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        if (selection.isNotEmpty()) {
            SelectionBar(
                count = selection.size,
                onSelectAll = onSelectAll,
                onClear = onClearSelection,
                onDownload = { showBatchDialog = true },
            )
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, stringResource(R.string.clear))
                        }
                    }
                    IconButton(onClick = {
                        readClipboard(context)?.let {
                            onQueryChange(it)
                            onSubmit()
                            keyboard?.hide()
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, stringResource(R.string.paste))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                onSubmit()
            }),
        )

        if (state.channelContext != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLeaveChannel)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(state.channelContext.name, style = MaterialTheme.typography.titleSmall)
            }
        } else {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.tab == SearchTab.VIDEOS,
                    onClick = { onTabChange(SearchTab.VIDEOS) },
                    label = { Text(stringResource(R.string.filter_videos)) },
                )
                FilterChip(
                    selected = state.tab == SearchTab.CHANNELS,
                    onClick = { onTabChange(SearchTab.CHANNELS) },
                    label = { Text(stringResource(R.string.filter_channels)) },
                )
            }
        }

        Spacer(Modifier.size(8.dp))

        when {
            state.loading -> CenterBox { CircularProgressIndicator() }

            state.error != null -> CenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = onSubmit) { Text(stringResource(R.string.retry)) }
                }
            }

            !state.hasSearched -> CenterBox {
                Text(
                    stringResource(R.string.empty_search),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            state.videos.isEmpty() && state.channels.isEmpty() -> CenterBox {
                Text(stringResource(R.string.no_results))
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.channels, key = { "c-${it.id}" }) { channel ->
                    ChannelRow(channel, onClick = { onChannelClick(channel) })
                }
                items(state.videos, key = { "v-${it.id}" }) { video ->
                    VideoRow(
                        video = video,
                        selectionMode = selection.isNotEmpty(),
                        selected = video.id in selection,
                        onClick = { onVideoClick(video) },
                        onLongClick = { onVideoLongClick(video) },
                    )
                }
                if (state.continuation != null) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator()
                            } else {
                                TextButton(onClick = onLoadMore) {
                                    Text(stringResource(R.string.load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, stringResource(R.string.clear))
            }
            Text(
                stringResource(R.string.selected_n, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all)) }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onDownload) {
                Text(stringResource(R.string.download_selected, count))
            }
        }
    }
}

/** One quality applied to every video in the batch. */
@Composable
private fun BatchQualityDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (height: Int, audioOnly: Boolean) -> Unit,
) {
    val heights = listOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144)
    var height by remember { mutableIntStateOf(1080) }
    var audioOnly by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quality_for_all)) },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { audioOnly = !audioOnly }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = audioOnly, onCheckedChange = { audioOnly = it })
                    Text(stringResource(R.string.audio_only))
                }
                if (!audioOnly) {
                    heights.forEach { value ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { height = value }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = height == value, onClick = { height = value })
                            Text(
                                if (value == 0) stringResource(R.string.best_available)
                                else "${value}p"
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(height, audioOnly) }) {
                Text(stringResource(R.string.download_selected, count))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    video: VideoItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(
                onClick = { if (selectionMode) onLongClick() else onClick() },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onLongClick() })
            Spacer(Modifier.width(4.dp))
        }
        Thumbnail(
            url = video.thumbnailUrl,
            overlay = if (video.isLive) "LIVE" else video.durationText,
            modifier = Modifier
                .width(148.dp)
                .aspectRatio(16f / 9f),
        )
        Spacer(Modifier.width(12.dp))
        TwoLine(
            title = video.title,
            subtitle = listOf(video.author, video.viewCountText, video.publishedText)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChannelRow(channel: ChannelItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(
            url = channel.thumbnailUrl,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(channel.videoCountText, channel.subscriberText)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = manager?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
