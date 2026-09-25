package com.minli.musicdl.data

import android.content.Context
import com.minli.musicdl.bridge.ProgressCallback
import com.minli.musicdl.bridge.YtDlpBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DownloadRepository(
    private val context: Context,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items = _items.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _ffmpegDir: String? by lazy {
        YtDlpBridge.setupFfmpeg(
            context.applicationInfo.nativeLibraryDir,
            context.filesDir,
        )
    }
    val ffmpegAvailable: Boolean get() = _ffmpegDir != null

    val downloadDir: File
        get() = context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")

    private fun update(key: String, transform: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.key == key) transform(it) else it }
    }

    private fun flag(key: String) = cancelFlags.getOrPut(key) { AtomicBoolean(false) }

    fun cancel(key: String) {
        flag(key).set(true)
    }

    fun delete(item: DownloadItem) {
        cancel(item.key)
        item.filePath?.let { runCatching { File(it).delete() } }
        item.musicUri?.let {
            runCatching {
                context.contentResolver.delete(android.net.Uri.parse(it), null, null)
            }
        }
        _items.value = _items.value.filter { it.key != item.key }
        cancelFlags.remove(item.key)
    }

    fun enqueue(track: TrackInfo, format: AudioFormat) {
        if (_items.value.any { it.track.id == track.id && it.status != DownloadStatus.DONE
                && it.status != DownloadStatus.FAILED && it.status != DownloadStatus.CANCELLED }) return
        val item = DownloadItem(track = track, format = format)
        _items.value = _items.value + item
        flag(item.key).set(false)
        com.minli.musicdl.service.DownloadService.ensureRunning(context)
        scope.launch { mutex.withLock { process(item) } }
    }

    private fun optionsJson(item: DownloadItem): String {
        val outtmpl = File(downloadDir, "%(title).120B [%(id)s].%(ext)s").absolutePath
        val opts = JSONObject()
        opts.put("outtmpl", JSONObject().put("default", outtmpl))
        opts.put("noplaylist", true)
        opts.put("retries", 3)
        _ffmpegDir?.let { opts.put("ffmpeg_location", it) }
        when (item.format) {
            AudioFormat.M4A_ORIGINAL ->
                opts.put("format", "bestaudio[ext=m4a]/bestaudio[acodec^='mp4a']/bestaudio/best")
            AudioFormat.M4A_AAC -> {
                opts.put("format", "bestaudio/best")
                opts.put("postprocessors", JSONArray().put(
                    JSONObject()
                        .put("key", "FFmpegExtractAudio")
                        .put("preferredcodec", "m4a")
                        .put("preferredquality", "256"),
                ))
            }
            AudioFormat.MP3_192, AudioFormat.MP3_320 -> {
                opts.put("format", "bestaudio/best")
                opts.put("postprocessors", JSONArray().put(
                    JSONObject()
                        .put("key", "FFmpegExtractAudio")
                        .put("preferredcodec", "mp3")
                        .put("preferredquality", if (item.format == AudioFormat.MP3_320) "320" else "192"),
                ))
            }
            AudioFormat.OPUS -> {
                opts.put("format", "bestaudio[ext=webm]/bestaudio/best")
                opts.put("postprocessors", JSONArray().put(
                    JSONObject()
                        .put("key", "FFmpegExtractAudio")
                        .put("preferredcodec", "opus")
                        .put("preferredquality", "0"),
                ))
            }
        }
        return opts.toString()
    }

    private suspend fun process(item: DownloadItem) {
        update(item.key) { it.copy(status = DownloadStatus.RUNNING, error = null) }
        flag(item.key).set(false)
        var lastDone = 0L
        var lastAt = 0L
        val result = try {
            YtDlpBridge.download(item.track.url, optionsJson(item), object : ProgressCallback {
                override fun onProgress(done: Long, total: Long, speed: Double, eta: Long) {
                    val now = System.currentTimeMillis()
                    if (done == lastDone && now - lastAt < 300) return
                    lastDone = done; lastAt = now
                    update(item.key) { it.copy(doneBytes = done, totalBytes = total, speed = speed, eta = eta) }
                }

                override fun isCancelled(): Boolean = flag(item.key).get()
            })
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "native error").toString()
        }
        val json = JSONObject(result)
        if (!json.optBoolean("ok")) {
            val cancelled = json.optBoolean("cancelled")
            update(item.key) {
                it.copy(
                    status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED,
                    error = if (cancelled) null else json.optString("error", "下载失败"),
                )
            }
            return
        }
        var path = json.optString("path")
        if (path.isEmpty()) {
            path = scanLatest(item.track.id) ?: ""
        }
        var musicUri: String? = null
        val file = path.takeIf { it.isNotEmpty() }?.let(::File)
        if (file != null && file.exists()) {
            if (settings.copyToMusic) {
                musicUri = MediaStoreImport.import(context, file)
            }
        }
        update(item.key) {
            it.copy(
                status = DownloadStatus.DONE,
                filePath = path,
                musicUri = musicUri,
                doneBytes = it.totalBytes.coerceAtLeast(1),
            )
        }
        delay(100)
    }

    private fun scanLatest(videoId: String): String? {
        val files = downloadDir.listFiles() ?: return null
        return files.filter { it.name.contains("[$videoId]") }
            .maxByOrNull { it.lastModified() }?.absolutePath
    }

    fun search(query: String, limit: Int, onResult: (List<TrackInfo>?, String?) -> Unit) {
        _isSearching.value = true
        scope.launch(Dispatchers.IO) {
            try {
                if (query.startsWith("http://") || query.startsWith("https://")) {
                    parseJsonInfo(YtDlpBridge.getInfo(query), onResult)
                } else {
                    parseJsonList(YtDlpBridge.search(query, limit), onResult)
                }
            } catch (e: Exception) {
                onResult(null, e.message ?: "搜索失败")
            } finally {
                _isSearching.value = false
            }
        }
    }

    private fun parseJsonList(json: String, onResult: (List<TrackInfo>?, String?) -> Unit) {
        val obj = JSONObject(json)
        if (!obj.optBoolean("ok")) { onResult(null, obj.optString("error")); return }
        val arr = obj.getJSONArray("results")
        val list = ArrayList<TrackInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            list.add(
                TrackInfo(
                    id = e.optString("id"),
                    title = e.optString("title"),
                    url = e.optString("url"),
                    durationSec = e.optLong("duration"),
                    artist = e.optString("artist").ifEmpty { null },
                    thumbnail = e.optString("thumbnail").ifEmpty { null },
                ),
            )
        }
        onResult(list, null)
    }

    private fun parseJsonInfo(json: String, onResult: (List<TrackInfo>?, String?) -> Unit) {
        parseJsonList(
            JSONObject(json).let { o ->
                if (o.optBoolean("ok")) {
                    JSONObject().put("ok", true).put("results", JSONArray().put(o.getJSONObject("result")))
                } else o
            }.toString(),
            onResult,
        )
    }

    fun ytdlpVersion(): String = YtDlpBridge.ytdlpVersion()

    companion object {
        val activeStatuses = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)
    }
}
