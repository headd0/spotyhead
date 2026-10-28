package com.music.spotui.data.offline

/**
 * Whether a song picked in a list plays from the phone (the downloaded file) or from the Web Player.
 *
 * Offline, or with the data saver on mobile data, the Web Player cannot stream: the list's
 * downloaded songs play. Online, a downloaded song also plays from its file (the one
 * the user chose to keep) as long as the whole list is on the phone: the local queue holds only
 * downloaded songs, so a list that is only partly downloaded would lose the others, and it keeps
 * streaming from Spotify's own queue.
 * 
 * When Offline Mode is enabled, playback NEVER falls back to online streaming—only downloaded
 * and local files can play.
 */
object LocalPlayPlan {
    /**
     * The ids to play locally, in play order, or null when the Web Player plays.
     * [ordered]: the list's track ids in the order to play them; [complete]: the whole list is loaded.
     * 
     * Returns null ONLY if the Web Player can stream. In offline mode or with data saver,
     * always returns a list of available downloaded tracks (never null, even if empty).
     */
    fun localIds(
        ordered: List<String>,
        picked: String,
        complete: Boolean,
        offline: Boolean,
        dataSaver: Boolean,
        isDownloaded: (String) -> Boolean,
    ): List<String>? {
        if (!isDownloaded(picked)) {
            // In offline/data-saver mode, a song MUST be downloaded to play
            if (offline || dataSaver) return emptyList()
            return null
        }
        val ids = ordered.filter { it.isNotBlank() }.distinct()
        val downloaded = ids.filter(isDownloaded)
        if (downloaded.isEmpty()) {
            // In offline/data-saver mode, if nothing is downloaded, return empty list (no fallback)
            if (offline || dataSaver) return emptyList()
            return null
        }
        // In offline or data-saver mode, play only what's downloaded (no partial fallback to streaming)
        if (offline || dataSaver) return downloaded
        // Online mode: play locally only if the whole list is downloaded
        return if (complete && downloaded.size == ids.size) downloaded else null
    }
}
