package com.minli.musicdl.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var defaultFormat: AudioFormat by mutableStateOf(readFormat())
        private set
    var copyToMusic: Boolean by mutableStateOf(prefs.getBoolean("copy_to_music", true))
        private set

    private fun readFormat(): AudioFormat {
        val name = prefs.getString("default_format", null) ?: return AudioFormat.M4A_ORIGINAL
        return runCatching { AudioFormat.valueOf(name) }.getOrDefault(AudioFormat.M4A_ORIGINAL)
    }

    fun updateDefaultFormat(f: AudioFormat) {
        defaultFormat = f
        prefs.edit().putString("default_format", f.name).apply()
    }

    fun updateCopyToMusic(v: Boolean) {
        copyToMusic = v
        prefs.edit().putBoolean("copy_to_music", v).apply()
    }
}
