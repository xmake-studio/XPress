package com.xpress.app.data

import android.content.Context
import androidx.core.content.edit
import com.xpress.app.engine.CompressSettings

/** Remembers the last used settings between launches. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): CompressSettings {
        val d = CompressSettings()
        return CompressSettings(
            mode = enumOr(prefs.getString("mode", null), d.mode),
            targetSizeMb = prefs.getFloat("targetSizeMb", d.targetSizeMb.toFloat()).toDouble(),
            videoBitrateKbps = prefs.getInt("videoBitrateKbps", d.videoBitrateKbps),
            quality = prefs.getInt("quality", d.quality),
            codec = enumOr(prefs.getString("codec", null), d.codec),
            resolution = prefs.getInt("resolution", d.resolution),
            fps = prefs.getInt("fps", d.fps),
            audioKbps = prefs.getInt("audioKbps", d.audioKbps),
            speed = enumOr(prefs.getString("speed", null), d.speed),
        )
    }

    fun save(s: CompressSettings) = prefs.edit {
        putString("mode", s.mode.name)
        putFloat("targetSizeMb", s.targetSizeMb.toFloat())
        putInt("videoBitrateKbps", s.videoBitrateKbps)
        putInt("quality", s.quality)
        putString("codec", s.codec.name)
        putInt("resolution", s.resolution)
        putInt("fps", s.fps)
        putInt("audioKbps", s.audioKbps)
        putString("speed", s.speed.name)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
