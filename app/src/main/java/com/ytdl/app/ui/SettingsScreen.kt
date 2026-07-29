package com.ytdl.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ytdl.app.BuildConfig
import com.ytdl.app.R
import com.ytdl.app.settings.Quality
import com.ytdl.app.settings.Settings
import com.ytdl.app.settings.ThemeMode

@Composable
fun SettingsScreen(
    settings: Settings,
    onHeightChange: (Int) -> Unit,
    onAudioOnlyDefault: (Boolean) -> Unit,
    onPreferWebm: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onParallel: (Int) -> Unit,
    onPickFolder: () -> Unit,
    onResetFolder: () -> Unit,
    onFilenameTemplate: (String) -> Unit,
    onSubtitles: (Boolean) -> Unit,
    onThumbnail: (Boolean) -> Unit,
    onInstantShare: (Boolean) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showQualityDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        Header(stringResource(R.string.settings_downloads))

        ClickableRow(
            title = stringResource(R.string.settings_default_quality),
            subtitle = qualityLabel(settings.defaultHeight),
            onClick = { showQualityDialog = true },
        )

        SwitchRow(
            title = stringResource(R.string.audio_only),
            subtitle = null,
            checked = settings.audioOnlyByDefault,
            onChange = onAudioOnlyDefault,
        )

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.settings_prefer_container),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !settings.preferWebm,
                    onClick = { onPreferWebm(false) },
                    label = { Text("MP4 / H.264") },
                )
                FilterChip(
                    selected = settings.preferWebm,
                    onClick = { onPreferWebm(true) },
                    label = { Text("WebM / VP9") },
                )
            }
        }

        SwitchRow(
            title = stringResource(R.string.settings_wifi_only),
            subtitle = stringResource(R.string.settings_wifi_only_sum),
            checked = settings.wifiOnly,
            onChange = onWifiOnly,
        )

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.settings_parallel),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { value ->
                    FilterChip(
                        selected = settings.maxParallel == value,
                        onClick = { onParallel(value) },
                        label = { Text("$value") },
                    )
                }
            }
        }

        HorizontalDivider()
        Header(stringResource(R.string.settings_storage))

        ClickableRow(
            title = stringResource(R.string.settings_folder),
            subtitle = settings.folderUri ?: stringResource(R.string.settings_folder_default),
            onClick = onPickFolder,
        )
        if (settings.folderUri != null) {
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = onResetFolder) {
                    Text(stringResource(R.string.settings_reset_folder))
                }
            }
        }

        ClickableRow(
            title = stringResource(R.string.settings_filename),
            subtitle = settings.filenameTemplate,
            onClick = { showTemplateDialog = true },
        )

        HorizontalDivider()
        Header(stringResource(R.string.settings_extras))

        SwitchRow(
            title = stringResource(R.string.settings_subtitles),
            subtitle = stringResource(R.string.settings_subtitles_sum),
            checked = settings.downloadSubtitles,
            onChange = onSubtitles,
        )
        SwitchRow(
            title = stringResource(R.string.settings_thumbnail),
            subtitle = null,
            checked = settings.saveThumbnail,
            onChange = onThumbnail,
        )

        HorizontalDivider()
        Header(stringResource(R.string.settings_sharing))

        SwitchRow(
            title = stringResource(R.string.settings_share_auto),
            subtitle = stringResource(R.string.settings_share_auto_sum),
            checked = settings.instantShareDownload,
            onChange = onInstantShare,
        )

        HorizontalDivider()
        Header(stringResource(R.string.settings_appearance))

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.themeMode == ThemeMode.SYSTEM,
                    onClick = { onTheme(ThemeMode.SYSTEM) },
                    label = { Text(stringResource(R.string.settings_theme_system)) },
                )
                FilterChip(
                    selected = settings.themeMode == ThemeMode.LIGHT,
                    onClick = { onTheme(ThemeMode.LIGHT) },
                    label = { Text(stringResource(R.string.settings_theme_light)) },
                )
                FilterChip(
                    selected = settings.themeMode == ThemeMode.DARK,
                    onClick = { onTheme(ThemeMode.DARK) },
                    label = { Text(stringResource(R.string.settings_theme_dark)) },
                )
            }
        }

        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            subtitle = null,
            checked = settings.dynamicColor,
            onChange = onDynamicColor,
        )

        HorizontalDivider()
        Header(stringResource(R.string.settings_about))
        Text(
            "YTdl ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    if (showQualityDialog) {
        QualityDialog(
            current = settings.defaultHeight,
            onDismiss = { showQualityDialog = false },
            onPick = {
                onHeightChange(it)
                showQualityDialog = false
            },
        )
    }

    if (showTemplateDialog) {
        TemplateDialog(
            current = settings.filenameTemplate,
            onDismiss = { showTemplateDialog = false },
            onConfirm = {
                onFilenameTemplate(it)
                showTemplateDialog = false
            },
        )
    }
}

@Composable
private fun qualityLabel(height: Int): String = when (height) {
    Quality.ASK -> stringResource(R.string.settings_ask_every_time)
    Quality.BEST -> stringResource(R.string.best_available)
    else -> "${height}p"
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ClickableRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QualityDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_default_quality)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Quality.CHOICES.forEach { value ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == value, onClick = { onPick(value) })
                        Text(qualityLabel(value))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}

@Composable
private fun TemplateDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_filename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.settings_filename_sum),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(value.trim()) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
