package com.ytdl.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ytdl.app.R
import com.ytdl.app.download.DownloadStatus
import com.ytdl.app.download.DownloadTask
import com.ytdl.app.download.Output

@Composable
fun DownloadsScreen(
    tasks: List<DownloadTask>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val queue = tasks.filter { !it.isFinished }.sortedWith(
        compareBy({ if (it.status == DownloadStatus.RUNNING) 0 else 1 }, { it.queueOrder })
    )
    val finished = tasks.filter { it.isFinished }.sortedByDescending { it.createdAt }

    if (tasks.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.downloads_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (queue.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(R.string.queue) + " · ${queue.size}",
                    actions = {
                        TextButton(onClick = onPauseAll) {
                            Text(stringResource(R.string.pause_all))
                        }
                        TextButton(onClick = onResumeAll) {
                            Text(stringResource(R.string.resume_all))
                        }
                    },
                )
            }
            items(queue, key = { it.id }) { task ->
                val position = queue.indexOf(task) + 1
                TaskRow(
                    task = task,
                    queuePosition = if (task.status == DownloadStatus.QUEUED) position else 0,
                    canMove = task.status == DownloadStatus.QUEUED ||
                        task.status == DownloadStatus.PAUSED,
                    onPause = { onPause(task.id) },
                    onResume = { onResume(task.id) },
                    onRemove = { onRemove(task.id) },
                    onMove = { up -> onMove(task.id, up) },
                    context = context,
                )
                HorizontalDivider()
            }
        }

        if (finished.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(R.string.finished) + " · ${finished.size}",
                    actions = {
                        TextButton(onClick = onClearFinished) {
                            Text(stringResource(R.string.clear_finished))
                        }
                    },
                )
            }
            items(finished, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    queuePosition = 0,
                    canMove = false,
                    onPause = { onPause(task.id) },
                    onResume = { onResume(task.id) },
                    onRemove = { onRemove(task.id) },
                    onMove = {},
                    context = context,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, actions: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun TaskRow(
    task: DownloadTask,
    queuePosition: Int,
    canMove: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Boolean) -> Unit,
    context: Context,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(
            url = task.thumbnailUrl,
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f),
        )
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                task.title.ifEmpty { task.displayName },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = statusLine(task, queuePosition, context),
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (task.status == DownloadStatus.RUNNING && task.progress > 0f) {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (task.status == DownloadStatus.MERGING ||
                task.status == DownloadStatus.SAVING ||
                task.status == DownloadStatus.RUNNING
            ) {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                when {
                    task.status == DownloadStatus.RUNNING ||
                        task.status == DownloadStatus.QUEUED -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, stringResource(R.string.pause))
                        }
                    }

                    task.status == DownloadStatus.PAUSED ||
                        task.status == DownloadStatus.FAILED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.resume))
                        }
                    }

                    task.status == DownloadStatus.COMPLETED && task.resultUri != null -> {
                        IconButton(onClick = { openFile(context, task) }) {
                            Icon(Icons.Default.OpenInNew, stringResource(R.string.open))
                        }
                        IconButton(onClick = { shareFile(context, task) }) {
                            Icon(Icons.Default.Share, stringResource(R.string.share))
                        }
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, stringResource(R.string.remove))
                }
            }
            if (canMove) {
                Row(horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { onMove(true) }) {
                        Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up))
                    }
                    IconButton(onClick = { onMove(false) }) {
                        Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down))
                    }
                }
            }
        }
    }
}

private fun statusLine(task: DownloadTask, queuePosition: Int, context: Context): String {
    val quality = task.qualityLabel.takeIf { it.isNotBlank() }
    return when (task.status) {
        DownloadStatus.QUEUED ->
            listOfNotNull(
                if (queuePosition > 0) {
                    context.getString(R.string.queue_position, queuePosition)
                } else context.getString(R.string.queued),
                quality,
            ).joinToString(" · ")

        DownloadStatus.RUNNING -> {
            val pct = "${(task.progress * 100).toInt()}%"
            listOfNotNull(context.getString(R.string.downloading), pct, quality)
                .joinToString(" · ")
        }

        DownloadStatus.MERGING -> context.getString(R.string.merging)
        DownloadStatus.SAVING -> context.getString(R.string.saving)
        DownloadStatus.WAITING_WIFI -> context.getString(R.string.err_wifi_only)
        DownloadStatus.PAUSED ->
            listOfNotNull(context.getString(R.string.paused), quality).joinToString(" · ")

        DownloadStatus.CANCELED -> context.getString(R.string.canceled)
        DownloadStatus.FAILED ->
            task.error ?: context.getString(R.string.failed)

        DownloadStatus.COMPLETED ->
            listOfNotNull(task.displayName, quality).joinToString(" · ")
    }
}

private fun openFile(context: Context, task: DownloadTask) {
    val uri = task.resultUri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), Output.mimeFor(task.fileExtension))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
}

private fun shareFile(context: Context, task: DownloadTask) {
    val uri = task.resultUri ?: return
    val intent = Intent(Intent.ACTION_SEND)
        .setType(Output.mimeFor(task.fileExtension))
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
}
