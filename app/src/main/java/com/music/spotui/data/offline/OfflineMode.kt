package com.music.spotui.data.offline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * User-controlled Offline Mode: only downloaded songs and Local Files play, regardless
 * of what Android reports about the network. Unlike [com.music.spotui.data.DataSaver],
 * it does not wait for mobile data — it stays on until the user turns it off.
 *
 * Automatic downloads and remote audio-source resolution stay off while it is enabled.
 * Playback never falls back to Spotify or an extension stream.
 */
object OfflineMode {
    private const val PREFS = "myspoti_prefs"
    internal const val KEY = "OfflineMode"

    var enabled by mutableStateOf(false)
        private set

    private var context: Context? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun init(c: Context) {
        if (context != null) return
        val app = c.applicationContext
        context = app
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(on: Boolean) {
        if (enabled == on) return
        enabled = on
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY, on)?.apply()
        main.post { applied(on) }
    }

    /** Playback that must not leave the phone (this switch or the data saver applying). */
    val localOnly: Boolean get() = enabled || com.music.spotui.data.DataSaver.active

    private fun applied(on: Boolean) {
        if (on) {
            runCatching { app.myspoti.host.android.MyspotiRuntime.suspendPlayer() }
            OfflineDownloader.holdTransfers()
            PodcastDownloader.holdTransfers()
            com.music.spotui.di.SongPlayer.onOfflineModeOn()
        } else {
            if (!com.music.spotui.data.DataSaver.active) {
                runCatching { app.myspoti.host.android.MyspotiRuntime.resume() }
            }
            // Pending downloads wait in the workers; they proceed on the next loop.
            if (!com.music.spotui.data.DataSaver.metered) {
                context?.let { OfflineCollections.onUnmetered(it) }
            }
        }
    }
}
