package com.minli.musicdl

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.minli.musicdl.data.AudioFormat
import com.minli.musicdl.data.DownloadItem
import com.minli.musicdl.data.DownloadRepository
import com.minli.musicdl.data.DownloadStatus
import com.minli.musicdl.data.MediaStoreImport
import com.minli.musicdl.data.TrackInfo
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

private enum class Tab { SEARCH, DOWNLOADS, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val shared = intent?.takeIf { it.action == android.content.Intent.ACTION_SEND }
            ?.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                MusicDLApp(initialQuery = shared)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicDLApp(initialQuery: String) {
    val repo = App.repository
    var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
    val allItems by repo.items.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.SEARCH,
                    onClick = { tab = Tab.SEARCH },
                    icon = { Icon(Icons.Default.Search, null) },
                    label = { Text(stringResource(R.string.tab_search)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.DOWNLOADS,
                    onClick = { tab = Tab.DOWNLOADS },
                    icon = {
                        Box {
                            Icon(Icons.Default.Download, null)
                            val n = allItems.count { it.status in DownloadRepository.activeStatuses }
                            if (n > 0) {
                                Box(Modifier.align(Alignment.TopEnd)) { Badge { Text("$n") } }
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.tab_downloads)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.SEARCH -> SearchScreen(repo, initialQuery)
                Tab.DOWNLOADS -> DownloadsScreen(repo)
                Tab.SETTINGS -> SettingsScreen(repo)
            }
        }
    }
}

@Composable
private fun SearchScreen(repo: DownloadRepository, initialQuery: String) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<TrackInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<TrackInfo?>(null) }
    val searching by repo.isSearching.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = query.isNotBlank() && !searching,
                onClick = {
                    error = null
                    results = null
                    repo.search(query.trim(), 15) { list, err ->
                        error = err
                        results = list
                    }
                },
            ) {
                if (searching) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.search_go))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        results?.let { list ->
            if (list.isEmpty()) Text(stringResource(R.string.search_empty))
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id + it.url }) { track ->
                    ResultRow(track) { picked = track }
                }
            }
        }
    }

    picked?.let { track ->
        FormatDialog(repo, track, onDismiss = { picked = null }) { fmt ->
            repo.enqueue(track, fmt)
            picked = null
        }
    }
}

@Composable
private fun ResultRow(track: TrackInfo, onDownload: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {},
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = track.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(80.dp, 45.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track.artist, fmtDuration(track.durationSec)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, stringResource(R.string.download))
            }
        }
    }
}

@Composable
private fun FormatDialog(
    repo: DownloadRepository,
    track: TrackInfo,
    onDismiss: () -> Unit,
    onConfirm: (AudioFormat) -> Unit,
) {
    var selected by remember { mutableStateOf(App.settings.defaultFormat) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_format)) },
        text = {
            Column {
                AudioFormat.entries.forEach { fmt ->
                    val enabled = !fmt.requiresFfmpeg || repo.ffmpegAvailable
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = enabled) { selected = fmt }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == fmt,
                            onClick = { if (enabled) selected = fmt },
                            enabled = enabled,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            fmt.label + if (!enabled) "（需要 FFmpeg）" else "",
                            color = if (enabled) Color.Unspecified
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DownloadsScreen(repo: DownloadRepository) {
    val items by repo.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_downloads), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(items, key = { it.key }) { item ->
            DownloadRow(repo, item)
        }
    }
}

@Composable
private fun DownloadRow(repo: DownloadRepository, item: DownloadItem) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(item.track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            when (item.status) {
                DownloadStatus.QUEUED -> Text(stringResource(R.string.status_queued),
                    style = MaterialTheme.typography.bodySmall)
                DownloadStatus.RUNNING -> {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(String.format(Locale.US, "%.1f%%", item.progress * 100))
                            if (item.totalBytes > 0) {
                                append("  ${fmtBytes(item.doneBytes)}/${fmtBytes(item.totalBytes)}")
                            }
                            if (item.speed > 0) append("  ${fmtBytes(item.speed.toLong())}/s")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DownloadStatus.DONE -> Text(
                    listOfNotNull(
                        stringResource(R.string.status_done),
                        item.format.label,
                        item.filePath?.let { fmtBytes(java.io.File(it).length()) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                DownloadStatus.FAILED -> Text(
                    item.error ?: stringResource(R.string.status_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                DownloadStatus.CANCELLED -> Text(stringResource(R.string.status_cancelled),
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                        OutlinedButton(onClick = { repo.cancel(item.key) }) {
                            Text(stringResource(R.string.cancel))
                        }
                    DownloadStatus.DONE ->
                        IconButton(onClick = { MediaStoreImport.openWith(context, item) }) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
                        }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
                        OutlinedButton(onClick = { repo.enqueue(item.track, item.format) }) {
                            Text(stringResource(R.string.retry))
                        }
                }
                IconButton(onClick = { repo.delete(item) }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(repo: DownloadRepository) {
    val settings = App.settings
    var fmtMenu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_output),
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { fmtMenu = true }) {
                        Text(settings.defaultFormat.label)
                    }
                    DropdownMenu(expanded = fmtMenu, onDismissRequest = { fmtMenu = false }) {
                        AudioFormat.entries.forEach { fmt ->
                            val enabled = !fmt.requiresFfmpeg || repo.ffmpegAvailable
                            DropdownMenuItem(
                                text = {
                                    Text(fmt.label,
                                        color = if (enabled) Color.Unspecified
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                enabled = enabled,
                                onClick = {
                                    settings.setDefaultFormat(fmt)
                                    fmtMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_copy_music), Modifier.weight(1f))
                    Switch(
                        checked = settings.copyToMusic,
                        onCheckedChange = { settings.setCopyToMusic(it) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_env),
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text("yt-dlp: ${repo.ytdlpVersion()}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "FFmpeg: " + if (repo.ffmpegAvailable) stringResource(R.string.env_ok)
                    else stringResource(R.string.env_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (repo.ffmpegAvailable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.settings_dir, repo.downloadDir.absolutePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmtDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun fmtBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val i = (ln(b.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size - 1)
    return String.format(Locale.US, "%.1f %s", b / 1024.0.pow(i), units[i])
}
