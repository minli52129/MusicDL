package com.minli.musicdl.bridge

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.io.File

interface ProgressCallback {
    fun onProgress(done: Long, total: Long, speed: Double, eta: Long)
    fun isCancelled(): Boolean
}

object YtDlpBridge {
    private val module: PyObject
        get() = Python.getInstance().getModule("ytdl")

    fun ytdlpVersion(): String = try {
        module.callAttr("ytdlp_version").toString()
    } catch (e: Exception) {
        "?"
    }

    /** Returns ffmpeg_location dir, or null when ffmpeg binaries are missing. */
    fun setupFfmpeg(nativeLibraryDir: String, workDir: File): String? = try {
        val r = module.callAttr("setup_ffmpeg", nativeLibraryDir, workDir.absolutePath)
        r?.toString()
    } catch (e: Exception) {
        null
    }

    fun search(rawQuery: String, limit: Int): String =
        module.callAttr("search", rawQuery, limit).toString()

    fun getInfo(url: String): String =
        module.callAttr("get_info", url).toString()

    fun download(url: String, optionsJson: String, progress: ProgressCallback): String =
        module.callAttr("download", url, optionsJson, progress).toString()
}
