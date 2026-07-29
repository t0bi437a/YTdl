package com.ytdl.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ytdl.app.settings.ThemeMode
import com.ytdl.app.ui.DownloadsScreen
import com.ytdl.app.ui.HomeScreen
import com.ytdl.app.ui.MainViewModel
import com.ytdl.app.ui.QualitySheet
import com.ytdl.app.ui.SettingsScreen
import com.ytdl.app.ui.theme.YTdlTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }

    private var pendingFolderPick: ((String) -> Unit)? = null

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            pendingFolderPick?.invoke(uri.toString())
        }
        pendingFolderPick = null
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the download still runs if it is declined; only the progress bar is lost */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val startOnDownloads = intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true

        setContent {
            val viewModel: MainViewModel = viewModel()
            val settings by viewModel.settings.collectAsState()

            YTdlTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppScaffold(
                    viewModel = viewModel,
                    startTab = if (startOnDownloads) 1 else 0,
                    onPickFolder = { onPicked ->
                        pendingFolderPick = onPicked
                        runCatching { folderPicker.launch(null) }
                            .onFailure {
                                Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
                            }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    viewModel: MainViewModel,
    startTab: Int,
    onPickFolder: ((String) -> Unit) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    val search by viewModel.search.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val picker by viewModel.picker.collectAsState()
    val batch by viewModel.batch.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val message by viewModel.messages.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val activeCount = downloads.count { it.isActive }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    when (tab) {
                        1 -> stringResource(R.string.tab_downloads)
                        2 -> stringResource(R.string.tab_settings)
                        else -> stringResource(R.string.app_name)
                    }
                )
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_search)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    label = {
                        Text(
                            if (activeCount > 0) {
                                stringResource(R.string.tab_downloads) + " ($activeCount)"
                            } else stringResource(R.string.tab_downloads)
                        )
                    },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                0 -> HomeScreen(
                    state = search,
                    selection = selection,
                    onQueryChange = viewModel::onQueryChange,
                    onSubmit = { viewModel.submit() },
                    onTabChange = viewModel::onTabChange,
                    onVideoClick = viewModel::openPicker,
                    onVideoLongClick = { viewModel.toggleSelection(it.id) },
                    onChannelClick = viewModel::openChannel,
                    onLeaveChannel = viewModel::leaveChannel,
                    onLoadMore = viewModel::loadMore,
                    onSelectAll = viewModel::selectAllVisible,
                    onClearSelection = viewModel::clearSelection,
                    onDownloadSelected = { height, audioOnly ->
                        viewModel.downloadSelected(height, audioOnly)
                        tab = 1
                    },
                )

                1 -> DownloadsScreen(
                    tasks = downloads,
                    onPause = viewModel::pauseDownload,
                    onResume = viewModel::resumeDownload,
                    onRemove = viewModel::removeDownload,
                    onMove = viewModel::moveInQueue,
                    onPauseAll = viewModel::pauseAll,
                    onResumeAll = viewModel::resumeAll,
                    onClearFinished = viewModel::clearFinished,
                )

                else -> {
                    val repo = viewModel.settingsRepository()
                    SettingsScreen(
                        settings = settings,
                        onHeightChange = { scope.launch { repo.setDefaultHeight(it) } },
                        onAudioOnlyDefault = { scope.launch { repo.setAudioOnly(it) } },
                        onPreferWebm = { scope.launch { repo.setPreferWebm(it) } },
                        onWifiOnly = { scope.launch { repo.setWifiOnly(it) } },
                        onParallel = { scope.launch { repo.setMaxParallel(it) } },
                        onPickFolder = {
                            onPickFolder { uri -> scope.launch { repo.setFolderUri(uri) } }
                        },
                        onResetFolder = { scope.launch { repo.setFolderUri(null) } },
                        onFilenameTemplate = { scope.launch { repo.setFilenameTemplate(it) } },
                        onSubtitles = { scope.launch { repo.setDownloadSubtitles(it) } },
                        onThumbnail = { scope.launch { repo.setSaveThumbnail(it) } },
                        onInstantShare = { scope.launch { repo.setInstantShare(it) } },
                        onTheme = { mode: ThemeMode -> scope.launch { repo.setThemeMode(mode) } },
                        onDynamicColor = { scope.launch { repo.setDynamicColor(it) } },
                    )
                }
            }

            batch?.let { state ->
                BatchBanner(
                    text = if (state.running) {
                        stringResource(R.string.batch_progress, state.done + state.failed, state.total)
                    } else {
                        stringResource(R.string.batch_done, state.done, state.failed)
                    },
                    running = state.running,
                    onDismiss = viewModel::dismissBatch,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    picker?.let { state ->
        QualitySheet(
            state = state,
            preferWebm = settings.preferWebm,
            onDismiss = viewModel::closePicker,
            onDownload = { info, video, audio -> viewModel.startDownload(info, video, audio) },
        )
    }
}

@Composable
private fun BatchBanner(
    text: String,
    running: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}
