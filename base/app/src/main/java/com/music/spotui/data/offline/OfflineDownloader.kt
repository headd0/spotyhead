package com.music.spotui.data.offline

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.isDownloadsEnabled
import com.music.spotui.di.Graph
import com.music.spotui.video.InterruptibleHttp
import com.music.spotui.video.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads songs for offline listening. Spotify only gives the track (title, artist,
 * duration, ISRC); where the audio file is, and that it is the right recording, is the job
 * of a `download-source` extension (docs/extensions/API.md §3), which answers the files it
 * has, best first. Myspoti then:
 *
 * 1. fetches the first file that works (a FLAC, a DASH manifest of FLAC frames joined
 *    into one FLAC, or an M4A / MP3);
 * 2. checks what any file must pass, whoever sent it: real, decodable audio whose length
 *    is the song's (Spotify's duration), so a preview or another cut is refused;
 * 3. keeps it encrypted ([OfflineCrypto]), with its cover, in the Downloaded list.
 *
 * No extension giving download files: downloads say to install one. Nothing that fails the
 * check is kept: the song is marked unavailable instead.
 */
object OfflineDownloader {
    private const val TAG = "Offline"
    private const val WORKERS = 3
    /** A file may be this much shorter than the song (seconds): more is a preview or a cut. */
    private const val SHORTER_MAX = 5
    /** And this much longer (trailing silence, a longer intro): more is another version. */
    private const val LONGER_MAX = 15

    data class Request(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: String,
        val durationMs: Long,
        val coverUrl: String,
        /** The quality chosen for this download ([DownloadQuality.name]); empty = the setting. */
        val quality: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", trackId).put("title", title).put("artist", artist)
            .put("album", album).put("albumId", albumId).put("duration", durationMs).put("cover", coverUrl)
            .put("quality", quality)

        companion object {
            fun of(s: SongsModel) = Request(
                trackId = s.spotifyTrackId, title = s.title, artist = s.singer, album = s.album,
                albumId = s.albumId, durationMs = s.durationMs.toLong(), coverUrl = s.coverUri,
            )
            fun fromJson(o: JSONObject) = Request(
                o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("albumId"), o.optLong("duration"), o.optString("cover"), o.optString("quality"),
            )
        }
    }

    private class Unavailable(message: String) : Exception(message)

    /** Two AAC encodes at once at most: the CPU stays free for the rest of the app. */
    private val encodeSlots = kotlinx.coroutines.sync.Semaphore(2)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Songs queued, downloading, or whose download did not succeed (for the Downloaded list). */
    val requests: SnapshotStateMap<String, Request> = mutableStateMapOf()
    private val queue = Channel<Request>(Channel.UNLIMITED)
    /** Requests not finished yet, in order (persisted: a restart resumes them). */
    private val pending = LinkedHashMap<String, Request>()
    private val running = ConcurrentHashMap<String, Job>()
    @Volatile private var started = false

    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            repeat(WORKERS) {
                scope.launch {
                    for (r in queue) {
                        // A worker must never die: `started` stays true, so a lost worker is
                        // never replaced and the whole queue would stop for good.
                        try {
                            work(r)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w(TAG, "${r.title}: the download worker recovered", t)
                            runCatching {
                                synchronized(pending) { pending.remove(r.trackId); savePending() }
                                setStatus(r.trackId, classifyFailure(t))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Re-queues what was waiting when the app last stopped. */
    fun resumePending() {
        // Read off the main thread (the app is already drawing): the queue fills a moment later.
        scope.launch {
            val saved = runCatching {
                val a = JSONArray(OfflineStore.pendingFile.readText())
                (0 until a.length()).map { Request.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            if (saved.isNotEmpty()) enqueueAll(saved)
        }
    }

    /**
     * Downloads [songs] in [quality] (asked when the setting is "ask each time"), else in
     * the quality chosen in Settings.
     */
    fun enqueue(
        songs: List<SongsModel>,
        quality: com.music.spotui.data.preferences.DownloadQuality? = null,
        /** Started by the app (a downloaded list keeping in step), not by a tap. */
        auto: Boolean = false,
    ) {
        // Downloads switched off in Settings: say so, instead of a list of failed songs.
        if (!isDownloadsEnabled(Graph.app)) {
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.downloads_off_toast, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        // No extension gives download files: say where to get one, instead of a list of failures.
        if (!com.music.spotui.extensions.Extensions.hasDownloadSource()) {
            if (!auto) scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.downloads_need_source, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        if (auto) {
            // Automatic downloads wait for Wi-Fi, and never run while Offline Mode is on.
            if (OfflineMode.enabled || com.music.spotui.data.DataSaver.metered || !OfflineStore.isOnline(Graph.app)) {
                if (!OfflineMode.enabled) OfflineCollections.waitForWifi()
                return
            }
        } else if (OfflineMode.enabled) {
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.download_offline_paused, android.widget.Toast.LENGTH_LONG).show()
            }
        } else if (com.music.spotui.data.DataSaver.metered) {
            // Asked for by the user on mobile data: it goes ahead, but they are told.
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.download_on_mobile_data, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val q = quality?.let { com.music.spotui.data.preferences.DownloadQuality.saved(it.name) }?.name.orEmpty()
        // Episodes go to PodcastDownloader: their id is no track id, so the download-source lookup
        // would 404 (or worse, match an unrelated recording by title).
        enqueueAll(songs.filter { it.spotifyTrackId.isNotBlank() && !isEpisodeKey(it.spotifyTrackId) }.map { Request.of(it).copy(quality = q) })
    }

    private fun enqueueAll(list: List<Request>) {
        val added = synchronized(pending) {
            list.filter { r ->
                // Last guard before the track download path, whatever the caller (resume, retry, list).
                !isEpisodeKey(r.trackId) &&
                    !OfflineStore.isDownloaded(r.trackId) && pending.putIfAbsent(r.trackId, r) == null
            }.also { if (it.isNotEmpty()) savePending() }
        }
        if (added.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            added.forEach { requests[it.trackId] = it; OfflineStore.status[it.trackId] = OfflineStatus.Queued }
        }
        start()
        added.forEach { queue.trySend(it) }
    }

    /** Stops a queued or running download (and forgets a failed one). */
    fun cancel(trackId: String) {
        synchronized(pending) { if (pending.remove(trackId) != null) savePending() }
        running.remove(trackId)?.cancel()
        OfflineStore.status.remove(trackId)
        requests.remove(trackId)
        dropTemps(trackId)
    }

    /**
     * Cancels transfers in flight without dropping them from the queue: Offline Mode (or
     * logout's pause) so they resume later instead of sitting as a running job with no worker.
     */
    fun holdTransfers() {
        running.values.forEach { it.cancel() }
    }

    /**
     * Stops every running and queued download and empties the queue. Unlike [removeAll], it
     * deletes nothing: finished songs stay in [OfflineStore.tracks] and in the index, and a
     * cancelled download only drops its own partial file in the temp folder.
     */
    fun stopAll() {
        // The queue survives a restart through pending.json: clearing it here is what makes the
        // stop stick, instead of the downloads resuming at the next launch.
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        // Running jobs may not all be in `pending` any more; cancel them by their own handle.
        running.values.forEach { it.cancel() }
        running.clear()
        // Snapshot state: the badges and the list are read from the UI, so write them there.
        scope.launch(Dispatchers.Main) {
            ids.forEach { OfflineStore.status.remove(it); requests.remove(it) }
        }
    }

    /** Tries a failed song again. */
    fun retry(trackId: String) {
        val r = requests[trackId] ?: return
        enqueueAll(listOf(r))
    }

    /** Removes the song: the file if downloaded, else its download. */
    fun remove(trackId: String) {
        cancel(trackId)
        // File deletion on the IO scope, not the (UI) caller's thread.
        scope.launch { OfflineStore.remove(trackId) }
    }

    fun removeAll() {
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        ids.forEach { running.remove(it)?.cancel() }
        running.values.forEach { it.cancel() }
        requests.clear()
        // Deleting a large library can take a while: IO scope, not the caller.
        scope.launch { OfflineCollections.removeAll(); OfflineStore.removeAll() }
    }

    private fun savePending() {
        runCatching {
            val a = JSONArray()
            pending.values.forEach { a.put(it.toJson()) }
            OfflineStore.pendingFile.writeText(a.toString())
        }
    }

    private suspend fun setStatus(id: String, s: OfflineStatus?) = withContext(Dispatchers.Main) {
        if (s == null) OfflineStore.status.remove(id) else OfflineStore.status[id] = s
    }

    private suspend fun work(r: Request) {
        // Cancelled while it waited in the queue.
        if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        // Offline Mode, or the data saver on mobile data: wait, shown as Waiting, not as a stuck download.
        while (transfersHeld()) {
            setStatus(r.trackId, OfflineStatus.Waiting)
            delay(1_000)
            if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        }
        val job = scope.launch {
            val outcome: OfflineStatus? = try {
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val t = download(r)
                // Already on the IO scope: the index rewrite stays off the main thread.
                OfflineStore.add(t)
                withContext(Dispatchers.Main) { requests.remove(r.trackId) }
                null
            } catch (e: CancellationException) {
                // Still queued (Offline Mode held the transfer): put it back so another worker
                // takes it. The status is set from a fresh coroutine: this one is cancelled, and
                // a suspending `setStatus` here would throw again before the re-queue, leaving
                // the song pending with no worker and no way to resume.
                val held = synchronized(pending) { pending.containsKey(r.trackId) }
                if (held) {
                    scope.launch(Dispatchers.Main) {
                        runCatching { OfflineStore.status[r.trackId] = OfflineStatus.Waiting }
                    }
                    queue.trySend(r)
                }
                // Do not remove from pending and do not touch the index: a held transfer
                // resumes later. A real cancel() already removed it from pending.
                if (held) return@launch
                // Cancelled by the user: clean up partial files and forget.
                dropTemps(r.trackId)
                synchronized(pending) { pending.remove(r.trackId); savePending() }
                scope.launch(Dispatchers.Main) { OfflineStore.status.remove(r.trackId); requests.remove(r.trackId) }
                return@launch
            } catch (e: Unavailable) {
                Log.i(TAG, "${r.title}: ${e.message}")
                OfflineStatus.Unavailable
            } catch (t: Throwable) {
                Log.w(TAG, "${r.title} failed", t)
                classifyFailure(t)
            }
            synchronized(pending) { pending.remove(r.trackId); savePending() }
            // The entry is out of the queue from here on: a failure to show the badge must
            // never leave the song stuck as downloading.
            if (outcome != null) runCatching { setStatus(r.trackId, outcome) }
        }
        running[r.trackId] = job
        try {
            job.join()
        } catch (e: CancellationException) {
            // The job was cancelled (holdTransfers / cancel): the job's own handler dealt with
            // re-queueing. Keep the worker alive.
        }
        running.remove(r.trackId, job)
    }

    private suspend fun download(r: Request): OfflineTrack {
        if (!isDownloadsEnabled(Graph.app)) throw Unavailable("downloads are off")
        val usable = OfflineStore.usableBytes()
        // For transcoding (FLAC→AAC), we need 2x the file size. Use 30MB as a safe estimate.
        if (!enoughSpace(usable, 30L * 1024 * 1024)) {
            throw IOException("not enough storage space")
        }
        val quality = qualityOf(r)
        val tmp = OfflineStore.tmpDir
        val audioTmp = File(tmp, "${r.trackId}.part")
        try {
            val meta = spotifyMeta(r)
            Log.i(TAG, "${meta.title}: isrc=${meta.isrc ?: "-"}")

            // ── The files the download-source extensions give, tried in their order; each one
            // is checked (real audio, the song's length) before it is taken.
            val file = resolveAndFetch(r, meta, audioTmp, quality)
            currentCoroutineContext().ensureActive()

            val out: File
            if (file.lossy != null) {
                // A small file as the source served it (M4A, MP3): kept as it is.
                out = File(OfflineStore.audioDir, "${r.trackId}.${file.lossy}")
                if (!commitFile(audioTmp, out)) throw IOException("could not move the file")
            } else if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) {
                // "Smallest": the checked FLAC becomes AAC, encoded on the phone.
                out = File(OfflineStore.audioDir, "${r.trackId}.m4a")
                val aacTmp = File(OfflineStore.tmpDir, "${r.trackId}.m4apart")
                try {
                    // Encoding is long: a few at a time, the other downloads go on meanwhile.
                    encodeSlots.withPermit { runInterruptible(Dispatchers.Default) { AacTranscoder.transcode(audioTmp, aacTmp) } }
                    if (aacTmp.length() < 10_000 || !commitFile(aacTmp, out)) throw IOException("could not encode the small file")
                } finally {
                    aacTmp.delete()
                }
            } else {
                out = File(OfflineStore.audioDir, "${r.trackId}.flac")
                if (!commitFile(audioTmp, out)) throw IOException("could not move the file")
            }
            // The audio is complete on disk from here on: "stop all" must not interrupt this last
            // part, or the file would stay out of the index and be swept as a stray at startup.
            return withContext(NonCancellable) {
                try {
                    // Kept encrypted: the file only plays in Myspoti, on this phone.
                    runInterruptible(Dispatchers.IO) { OfflineCrypto.encryptInPlace(Graph.app, out) }
                    val cover = runCatching {
                        val c = File(OfflineStore.coverDir, "${r.trackId}.jpg")
                        httpGet(app.myspoti.host.android.Covers.large(meta.coverUrl), c); c.name
                    }.getOrDefault("")
                    OfflineTrack(
                        trackId = r.trackId, title = meta.title, artist = meta.artist, album = meta.album,
                        albumId = meta.albumId, durationMs = meta.durationMs, coverUrl = meta.coverUrl,
                        file = out.name, cover = cover, source = file.provider, verified = file.isrcMatched,
                        bytes = out.length(), addedAt = System.currentTimeMillis(), providerId = file.extensionId,
                    )
                } catch (e: Throwable) {
                    // If encryption fails, clean up the unencrypted file
                    out.delete()
                    throw e
                }
            }
        } finally {
            audioTmp.delete()
        }
    }

    private class Fetched(
        val provider: String,
        val isrcMatched: Boolean,
        /** The id of the download-source extension that supplied it. */
        val extensionId: String,
        /** The extension of a lossy file kept as served (m4a, mp3); null for a FLAC. */
        val lossy: String? = null,
    )

    /** This download's quality: the one chosen when it started, else Settings (CD when Settings says "ask"). */
    private fun qualityOf(r: Request): com.music.spotui.data.preferences.DownloadQuality =
        com.music.spotui.data.preferences.DownloadQuality.saved(r.quality)
            ?: com.music.spotui.data.preferences.DownloadQuality.saved(com.music.spotui.data.preferences.getDownloadQuality(Graph.app).name)
            ?: com.music.spotui.data.preferences.DownloadQuality.CD

    /** The quality as a download-source extension is asked for it. */
    internal fun queryQuality(q: com.music.spotui.data.preferences.DownloadQuality): String = when (q) {
        com.music.spotui.data.preferences.DownloadQuality.HI_RES -> "hi-res"
        com.music.spotui.data.preferences.DownloadQuality.SMALL -> "small"
        else -> "cd"
    }

    /**
     * Asks the download-source extensions for [meta]'s files and saves the first one that works
     * into [out]. Unavailable when no extension has a file, or none of them could be used.
     */
    private suspend fun resolveAndFetch(r: Request, meta: Meta, out: File, quality: com.music.spotui.data.preferences.DownloadQuality): Fetched {
        val track = com.music.spotui.extensions.Extensions.trackInfo(r.trackId, meta.title, meta.artist, meta.album, meta.durationMs, meta.coverUrl)
            .copy(isrc = meta.isrc)
        // Show "searching..." state while looking for the source
        setStatus(r.trackId, OfflineStatus.Downloading(-1f))
        val all = com.music.spotui.extensions.Extensions.downloadFilesAll(track, queryQuality(quality))
        if (all.isEmpty()) throw Unavailable("no download source has this song")
        for ((extensionId, extension, files) in all) {
            for (file in files) {
                currentCoroutineContext().ensureActive()
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val label = listOfNotNull(file.provider ?: extension, file.bitDepth?.takeIf { file.lossless }?.let { "$it-bit" }).joinToString(" ")
                val ok = try {
                    when {
                        file.format == "dash-flac" -> runInterruptible(Dispatchers.IO) {
                            DashFlacRemux.download("data:application/dash+xml;base64,${file.manifest}", out, file.headers)
                        }
                        else -> fetchWithProgress(r.trackId, file.url!!, out, file.headers)
                    } && out.length() >= 10_000
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${meta.title}: $label ${file.format}: ${e.message}")
                    false
                }
                if (!ok) continue
                setStatus(r.trackId, OfflineStatus.Verifying)
                val refused = runCatching {
                    if (file.lossless) checkFlacHeader(out, meta, file.bitDepth) else checkAudio(out, meta)
                }.exceptionOrNull()
                if (refused != null) { Log.i(TAG, "${meta.title}: $label: ${refused.message}"); continue }
                if (!file.lossless) {
                    Log.i(TAG, "${meta.title}: saved $label ${file.format}")
                    return Fetched(label, file.isrcMatched, extensionId, lossy = file.format)
                }
                Log.i(TAG, "${meta.title}: saved FLAC from $label")
                return Fetched(label, file.isrcMatched, extensionId)
            }
        }
        throw Unavailable("none of the files from any download source could be used")
    }

    /**
     * A STREAMINFO read on the saved file: anything that is not a FLAC file (an encrypted or
     * AAC stream) is refused, a preview or another cut is caught (the header states the real
     * length), and a misplaced 24-bit label shows up in the log.
     */
    private fun checkFlacHeader(file: File, meta: Meta, bitDepth: Int?) {
        val seconds = runCatching {
            file.inputStream().use { s ->
                val head = ByteArray(42); var off = 0
                while (off < head.size) { val n = s.read(head, off, head.size - off); if (n < 0) break; off += n }
                flacDurationSec(head)
            }
        }.getOrNull() ?: throw Unavailable("the source did not send a FLAC file")

        checkLength(seconds.toLong(), meta)

        Log.d(TAG, "${meta.title}: FLAC ${seconds}s header check, labelled ${bitDepth ?: "-"}-bit")
    }

    /** An M4A / MP3 as served: the phone's own extractor must find an audio track of the song's length. */
    private fun checkAudio(file: File, meta: Meta) {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw Unavailable("the source did not send an audio file")
            val seconds = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) format.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000_000 else -1L
            if (seconds > 0) checkLength(seconds, meta)
        } catch (e: Unavailable) {
            throw e
        } catch (e: Throwable) {
            throw Unavailable("the source did not send an audio file")
        } finally {
            extractor.release()
        }
    }

    private fun checkLength(seconds: Long, meta: Meta) {
        lengthMismatch(seconds, meta.durationMs)?.let { throw Unavailable("backed out: $it") }
    }

    private data class Meta(
        val title: String, val artist: String, val album: String, val albumId: String,
        val durationMs: Long, val coverUrl: String, val isrc: String?,
    )

    /** The request, completed by Spotify's track (ISRC, and anything the list lacked). */
    private suspend fun spotifyMeta(r: Request): Meta {
        val api = app.myspoti.host.android.MyspotiRuntime.api
        val uri = "spotify:track:${r.trackId}"
        val t = runCatching { api.catalog.track(uri) }.onFailure { Log.w(TAG, "${r.title}: Spotify track: $it") }.getOrNull()
        val isrc = runCatching { api.catalog.isrc(uri) }.onFailure { Log.w(TAG, "${r.title}: ISRC: $it") }.getOrNull()
        val cover = r.coverUrl.ifBlank { t?.album?.images?.maxByOrNull { it.width ?: 0 }?.url.orEmpty() }
        return Meta(
            title = r.title.ifBlank { t?.name.orEmpty() },
            artist = r.artist.ifBlank { t?.artists?.joinToString(", ") { it.name }.orEmpty() },
            album = r.album.ifBlank { t?.album?.name.orEmpty() },
            albumId = r.albumId.ifBlank { t?.album?.uri?.substringAfterLast(':').orEmpty() },
            durationMs = r.durationMs.takeIf { it > 0 } ?: t?.durationMs ?: 0L,
            coverUrl = cover,
            isrc = isrc?.let(::normalizeIsrc),
        ).also { if (it.title.isBlank() || it.artist.isBlank()) throw Unavailable("no track metadata") }
    }

    private suspend fun httpGet(url: String, out: File) = runInterruptible {
        InterruptibleHttp.read(YouTubeSource.http.newCall(okhttp3.Request.Builder().url(url).build())) { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("empty body")
            out.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    /**
     * Downloads the file URL into [out] with HTTP **Range** requests in chunks,
     * reporting progress: a plain streaming GET with a browser User-Agent and the
     * extension's headers, retried per chunk on resets.
     */
    private suspend fun fetchWithProgress(trackId: String, url: String, out: File, headers: Map<String, String> = emptyMap()): Boolean {
        // A quick HEAD for the size: the progress bar needs it (-1 when the server says nothing).
        val total = runInterruptible(Dispatchers.IO) { contentLength(url, headers) }
        val watcher = if (total > 0) scope.launch {
            while (isActive) {
                setStatus(trackId, OfflineStatus.Downloading((out.length().toFloat() / total).coerceIn(0f, 1f)))
                delay(400)
            }
        } else null
        try {
            return runInterruptible(Dispatchers.IO) { rangedDownload(url, out, total, headers) }
        } finally {
            watcher?.cancel()
            setStatus(trackId, OfflineStatus.Downloading(-1f))
        }
    }

    /**
     * The byte range loop, shared with [PodcastDownloader]: a publisher's MP3 is fetched the
     * same way (follows the 302s to podtrac/radiofrance/simplecast, retries each chunk with
     * exponential backoff: 500ms, 1s, 2s).
     */
    internal fun rangedDownload(url: String, out: File, knownTotal: Long, headers: Map<String, String> = emptyMap()): Boolean {
        val chunk = 4L * 1024 * 1024 // 4 MB: the stream gets its turn between two chunks
        var total = knownTotal
        java.io.RandomAccessFile(out, "rw").use { output ->
            output.setLength(0)
            while (true) {
                // A chunk is [position, end], both worked out for each attempt: a retry resumes
                // where the bytes stopped, so a stale end would ask for a range that starts
                // past its own end (the server answers 416 and the song failed for good).
                var done = false
                var failed: IOException? = null
                var attempt = 0
                yieldToPlayer()
                while (true) {
                    attempt++
                    val position = output.length()
                    // The size HEAD gave says the file is already complete.
                    if (total > 0 && position >= total) { done = true; break }
                    val end = if (total > 0) minOf(position + chunk - 1, total - 1) else position + chunk - 1
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000  // Increased from 20s to 30s
                        readTimeout = 90_000      // Increased from 60s to 90s for large chunks
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", DownloadUa)
                        headers.forEach { (k, v) -> setRequestProperty(k, v) }
                        setRequestProperty("Range", "bytes=$position-$end")
                    }
                    try {
                        val code = conn.responseCode
                        // Asked past the end of a file whose size is unknown, or whose HEAD
                        // was wrong: the bytes on disk are all there is.
                        if (code == 416 && position > 0) { done = true; break }
                        if (code !in 200..299) throw IOException("stream HTTP $code")
                        val full = code != 206 // server ignored Range → the whole body, from byte 0
                        if (full) output.setLength(0)
                        if (total <= 0) total = contentRangeTotal(conn.getHeaderField("Content-Range")) ?: -1L
                        output.seek(output.length())
                        val expected = if (full) -1L else end - position + 1
                        var got = 0L
                        conn.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                got += n
                            }
                        }
                        when {
                            full -> done = true
                            // The whole file is on disk (the size is known, and reached).
                            total > 0 && output.length() >= total -> done = true
                            // No size known: a chunk shorter than asked ends the stream.
                            total <= 0 && got < expected -> done = true
                            // A known size and an empty answer: the server gave nothing, but
                            // the file is not complete: ask again rather than loop forever.
                            got == 0L -> throw IOException("stream sent no bytes at $position of $total")
                        }
                        break
                    } catch (e: IOException) {
                        if (attempt >= 3) { failed = e; break }
                        // Exponential backoff: 500ms, 1s, 2s
                        val backoffMs = 500L * (1 shl (attempt - 1))
                        Log.w(TAG, "chunk at ${output.length()} failed (attempt $attempt, backoff ${backoffMs}ms): ${e.message}")
                        Thread.sleep(backoffMs)
                    } finally {
                        conn.disconnect()
                    }
                }
                failed?.let { throw it }
                if (done) break
                if (total in 1..output.length()) break
            }
        }
        return true
    }

    /**
     * The song playing here is loading: downloads wait (up to 30 s) so they do not starve
     * it, or the player's stall watchdog would take it for stuck and skip it.
     */
    private fun yieldToPlayer() {
        var waited = 0
        while (waited < 30_000 && playerStarving()) {
            Thread.sleep(500); waited += 500
        }
    }

    private fun playerStarving(): Boolean = runCatching {
        com.music.spotui.di.SongPlayer.isPlaying() && com.music.spotui.di.SongPlayer.isBuffering &&
            com.music.spotui.di.SongPlayer.activeDevice.value == null && !com.music.spotui.di.LocalPlayer.active.value
    }.getOrDefault(false)

    private fun contentLength(url: String, headers: Map<String, String> = emptyMap()): Long =
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 30_000  // Increased from 15s to 30s
                readTimeout = 30_000      // Increased from 15s to 30s
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", DownloadUa)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1L)

    // ── Pure helpers (unit-tested) ──

    /** File hosts expect a browser-ish User-Agent on downloads. */
    internal const val DownloadUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Length from a FLAC STREAMINFO header, or null if [head] is not one. */
    internal fun flacDurationSec(head: ByteArray): Int? {
        if (head.size < 26 || String(head, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        // Bytes 18..25: sample rate (20 bits), channels (3), bits/sample (5), total samples (36).
        var bits = 0L
        for (i in 18..25) bits = (bits shl 8) or (head[i].toLong() and 0xFF)
        val sampleRate = (bits ushr 44).toInt()
        val totalSamples = bits and 0xFFFFFFFFFL
        if (sampleRate <= 0 || totalSamples <= 0) return null
        return (totalSamples / sampleRate).toInt()
    }

    /** Why a file of [seconds] is not the song of [durationMs], or null when it is (or the song's length is unknown). */
    internal fun lengthMismatch(seconds: Long, durationMs: Long): String? {
        val expected = durationMs / 1000
        return when {
            expected <= 0 -> null
            seconds < expected - SHORTER_MAX -> "the file is ${seconds}s, the song ${expected}s (a preview)"
            seconds > expected + LONGER_MAX -> "the file is ${seconds}s, the song ${expected}s (another version)"
            else -> null
        }
    }

    /** "bytes 0-99/1234" → 1234; null when absent or "*". */
    fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** "US-UM7-17-03861" / "usum71703861" → "USUM71703861"; null when not an ISRC. */
    fun normalizeIsrc(raw: String): String? =
        raw.uppercase().filter { it.isLetterOrDigit() }.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}").matches(it) }

    internal fun transfersHeld(): Boolean =
        OfflineMode.enabled || com.music.spotui.data.DataSaver.active

    internal fun dropTemps(trackId: String) {
        runCatching {
            // Clean all temporary files for this track, regardless of state
            File(OfflineStore.tmpDir, "$trackId.part").delete()
            File(OfflineStore.tmpDir, "$trackId.m4apart").delete()
            File(OfflineStore.audioDir, "$trackId.part").delete()
            File(OfflineStore.audioDir, "$trackId.m4apart").delete()
            // Also clean incomplete audio files that might have been left behind
            File(OfflineStore.audioDir, "$trackId.flac.tmp").delete()
            File(OfflineStore.audioDir, "$trackId.m4a.tmp").delete()
            File(OfflineStore.audioDir, "$trackId.mp3.tmp").delete()
        }
    }

    internal fun classifyFailure(t: Throwable): OfflineStatus {
        val msg = t.message?.lowercase().orEmpty()
        val cause = t.cause?.message?.lowercase().orEmpty()
        return when {
            t is java.io.InterruptedIOException -> OfflineStatus.Failed(app.myspoti.engine.R.string.download_error_network)
            t is java.net.SocketTimeoutException || t is java.net.UnknownHostException || t is java.net.ConnectException ->
                OfflineStatus.Failed(app.myspoti.engine.R.string.download_error_network)
            msg.contains("space") || msg.contains("enospc") || msg.contains("storage") || cause.contains("enospc") ->
                OfflineStatus.Failed(app.myspoti.engine.R.string.download_error_storage)
            msg.contains("verify") || msg.contains("preview") || msg.contains("corrupt") || msg.contains("header") || msg.contains("format") ->
                OfflineStatus.Failed(app.myspoti.engine.R.string.download_error_corrupt)
            msg.contains("404") || msg.contains("410") || msg.contains("not found") ->
                OfflineStatus.Failed(app.myspoti.engine.R.string.download_error_network)
            msg.contains("403") || msg.contains("forbidden") || msg.contains("access denied") ->
                OfflineStatus.Failed(app.myspoti.engine.R.string.download_error_network)
            else -> OfflineStatus.Failed(app.myspoti.engine.R.string.podcast_download_failed)
        }
    }
}
