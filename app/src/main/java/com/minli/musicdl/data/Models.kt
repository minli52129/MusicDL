package com.minli.musicdl.data

import java.util.UUID

enum class AudioFormat(val label: String, val requiresFfmpeg: Boolean) {
    M4A_ORIGINAL("M4A（原始音频流，无需转换）", false),
    M4A_AAC("M4A（AAC 转码）", true),
    MP3_192("MP3 192 kbps", true),
    MP3_320("MP3 320 kbps", true),
    OPUS("Opus（转封装/转码）", true),
}

data class TrackInfo(
    val id: String,
    val title: String,
    val url: String,
    val durationSec: Long,
    val artist: String?,
    val thumbnail: String?,
)

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadItem(
    val key: String = UUID.randomUUID().toString(),
    val track: TrackInfo,
    val format: AudioFormat,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val speed: Double = 0.0,
    val eta: Long = 0,
    val filePath: String? = null,
    val musicUri: String? = null,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        else if (status == DownloadStatus.DONE) 1f else 0f
}
