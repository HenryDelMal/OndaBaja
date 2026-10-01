package com.henry.encodec.player

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.cuy.emergencyradio.BuildConfig
import cl.cuy.emergencyradio.R
import com.henry.encodec.decoder.VocosDecoder
import com.henry.encodec.decoder.EncodecDecoder
import com.henry.encodec.ecdc.EcdcHeader
import com.henry.encodec.ecdc.EcdcReader
import com.henry.encodec.ecdc.EncodecVariant
import com.henry.encodec.playback.EcdcPlaybackSession
import com.henry.encodec.playback.AudioTrackSink
import com.henry.encodec.playback.LiveEcdcPlaybackSession
import com.henry.encodec.playback.LiveEcdcSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.io.FilterInputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PlaylistItem(
    val uri: Uri,
    val title: String,
    val header: EcdcHeader,
)

data class SavedLiveStream(
    val manifestUrl: String,
    val title: String,
    val variant: EncodecVariant? = null,
    val codebooks: Int? = null,
    val bandwidthKbps: Double? = null,
)

data class LiveUiState(
    val manifestUrl: String,
    val title: String,
    val status: String = "Connecting…",
    val sequence: Long? = null,
    val codebooks: Int? = null,
    val bandwidthKbps: Double? = null,
    val variant: EncodecVariant? = null,
    val bufferedSegments: Int = 0,
    val targetBufferedSegments: Int = 2,
    val buffering: Boolean = true,
)

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val region: String? = null,
    val description: String? = null,
)

enum class RepeatMode {
    OFF,
    TRACK,
    LIST;

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

data class PlayerState(
    val preparingModel: Boolean = false,
    val modelReady: Boolean = false,
    val catalog: List<RadioStation> = emptyList(),
    val currentStationId: String? = null,
    val loadingCatalog: Boolean = false,
    /** Playback queue. Kept separate from the user's saved track library. */
    val playlist: List<PlaylistItem> = emptyList(),
    val libraryTracks: List<PlaylistItem> = emptyList(),
    val livestreams: List<SavedLiveStream> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val progress: Float = 0f,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val addingUrl: Boolean = false,
    val live: LiveUiState? = null,
    val error: String? = null,
    val diagnosticsEnabled: Boolean = false,
    val experimentalVocos: Boolean = true,
    val rescaleEnabled: Boolean = false,
    val recentItems: List<String> = emptyList(),
) {
    val current: PlaylistItem? get() = playlist.getOrNull(currentIndex)
}

private data class MediaPublishKey(
    val currentUri: String?,
    val liveUrl: String?,
    val playing: Boolean,
    val paused: Boolean,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val positionSecond: Long,
    val livePhase: String?,
)

internal fun requiredLiveBufferDepth(deliveredSegments: Int, rebufferTarget: Int): Int =
    if (deliveredSegments == 0) 1 else rebufferTarget.coerceAtLeast(1)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val playlistStore = PlaylistStore(application)
    private val mutableState = MutableStateFlow(playlistStore.load())
    val state = mutableState.asStateFlow()
    private var playbackJob: Job? = null
    @Volatile private var session: EcdcPlaybackSession? = null
    @Volatile private var liveSession: LiveEcdcPlaybackSession? = null
    private var playbackGeneration = 0L
    private var requestedStartSample = 0L
    private val shufflePlayedUris = mutableSetOf<String>()
    private val decoderMutex = Mutex()
    private val liveDownloadDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EnCodecLiveDownloader")
    }.asCoroutineDispatcher()
    private var cachedDecoder: EncodecDecoder? = null
    private var cachedAudioSink: AudioTrackSink? = null
    private val remoteHeaderPrefixes = ConcurrentHashMap<String, ByteArray>()
    private var stationCatalogJob: Job? = null
    private var modelPreparationJob: Job? = null
    @Volatile private var stationNavigationIds: List<String>? = null
    private val mediaSession = MediaSession(application, "OndaBaja").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                val selected = mutableState.value.catalog.firstOrNull { it.id == mutableState.value.currentStationId }
                if (selected != null && !mutableState.value.playing) playStation(selected) else play()
            }
            // A live stream has no useful paused position. Treat the system
            // media bubble's pause command as Stop so Play reconnects live.
            override fun onPause() {
                if (mutableState.value.live != null) stop() else pause()
            }
            override fun onStop() = stop()
            override fun onSkipToNext() = nextStation()
            override fun onSkipToPrevious() = previousStation()
        })
        isActive = true
    }

    init {
        clearStalePlaybackService()
        prepareModel()
        LiveDiagnostics.enabled = mutableState.value.diagnosticsEnabled
        removeObsoleteDecoderModels()
        activeInstance = WeakReference(this)
        viewModelScope.launch {
            // Playback position changes frequently. Android's media session can
            // extrapolate between updates, so rebuilding the notification for
            // every UI progress tick only steals time from Compose and audio.
            state.distinctUntilChangedBy(::mediaPublishKey).collect(::publishMediaState)
        }
    }

    fun prepareModel() {
        if (modelPreparationJob?.isActive == true) return
        modelPreparationJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { decoderMutex.withLock { installVocosModel() } }
                mutableState.update { it.copy(modelReady = true, preparingModel = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(modelReady = false, preparingModel = false,
                    error = "Could not prepare app: ${error.message}") }
            }
        }
    }

    fun loadStationCatalog() {
        stationCatalogJob?.cancel()
        stationCatalogJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loadingCatalog = true, error = null)
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val connection = (URL(BuildConfig.STATION_CATALOG_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = BuildConfig.CATALOG_CONNECT_TIMEOUT_MS
                        readTimeout = BuildConfig.CATALOG_READ_TIMEOUT_MS
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        require(connection.responseCode in 200..299) { "Station list returned HTTP ${connection.responseCode}" }
                        val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                        val entries = root.optJSONArray("stations") ?: root.optJSONArray("streams")
                            ?: error("JSON must contain a 'stations' array")
                        buildList {
                            for (i in 0 until entries.length()) {
                                val item = entries.optJSONObject(i) ?: continue
                                val name = (item.optString("name").takeIf(String::isNotBlank)
                                    ?: item.optString("title").takeIf(String::isNotBlank)) ?: continue
                                val rawStream = (item.optString("url").takeIf(String::isNotBlank)
                                    ?: item.optString("streamUrl").takeIf(String::isNotBlank)
                                    ?: item.optString("stream_url").takeIf(String::isNotBlank)) ?: continue
                                val stream = normalizeStationUrl(rawStream) ?: continue
                                val uri = Uri.parse(stream)
                                if (!(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) || uri.host.isNullOrBlank()) continue
                                add(RadioStation(
                                    id = item.optString("id").takeIf(String::isNotBlank) ?: stream,
                                    name = name,
                                    streamUrl = stream,
                                    region = item.optString("region").takeIf(String::isNotBlank)
                                        ?: item.optString("location").takeIf(String::isNotBlank),
                                    description = item.optString("description").takeIf(String::isNotBlank),
                                ))
                            }
                        }.distinctBy { it.id }
                    } finally {
                        connection.disconnect()
                    }
                }
            }
            mutableState.value = if (loaded.isSuccess) {
                mutableState.value.copy(catalog = loaded.getOrThrow(), loadingCatalog = false,
                    error = if (loaded.getOrThrow().isEmpty()) "No valid stations were found in the radio list." else null)
            } else mutableState.value.copy(loadingCatalog = false,
                error = "Could not load radio list: ${loaded.exceptionOrNull()?.message ?: "network error"}")
        }
    }

    fun playStation(station: RadioStation) {
        if (!mutableState.value.modelReady) return
        val current = mutableState.value
        val normalized = current.catalog.firstOrNull { it.id == station.id } ?: station
        if (current.currentStationId == normalized.id && current.live != null) {
            if (current.paused) play() else pause()
            return
        }
        stopInternal(resetProgress = true)
        mutableState.value = current.copy(
            catalog = if (current.catalog.any { it.id == normalized.id }) current.catalog else current.catalog + normalized,
            currentStationId = normalized.id,
            live = LiveUiState(manifestUrl = normalized.streamUrl, title = normalized.name, status = "Connecting…"),
            playing = false,
            paused = false,
            progress = 0f,
            error = null,
        )
        openLive(normalized.streamUrl, normalized)
    }

    fun nextStation() = stepCatalogStation(1)
    fun previousStation() = stepCatalogStation(-1)

    fun setStationNavigationList(stationIds: List<String>) {
        val distinctIds = stationIds.distinct()
        if (stationNavigationIds != distinctIds) stationNavigationIds = distinctIds
    }

    private fun stepCatalogStation(delta: Int) {
        val state = mutableState.value
        val navigationIds = stationNavigationIds
        val stationList = if (navigationIds == null) state.catalog else
            navigationIds.mapNotNull { id -> state.catalog.firstOrNull { it.id == id } }
        if (stationList.isEmpty()) return
        val index = stationList.indexOfFirst { it.id == state.currentStationId }
        val next = if (index < 0) {
            if (delta > 0) 0 else stationList.lastIndex
        } else {
            (index + delta + stationList.size) % stationList.size
        }
        playStation(stationList[next])
    }

    fun addToPlaylist(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val existing = mutableState.value.libraryTracks.map { it.uri }.toSet()
        val accepted = mutableListOf<PlaylistItem>()
        val errors = mutableListOf<String>()

        uris.filterNot(existing::contains).forEach { uri ->
            runCatching {
                val header = resolver.openInputStream(uri)!!.use(EcdcReader::inspect)
                validateHeader(header)
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                PlaylistItem(uri, displayName(uri), header)
            }.onSuccess(accepted::add).onFailure {
                errors += "${displayName(uri)}: ${it.message}"
            }
        }

        val old = mutableState.value
        val combined = old.playlist + accepted
        val knownLibrary = old.libraryTracks.mapTo(mutableSetOf()) { it.uri }
        val library = old.libraryTracks + accepted.filter { knownLibrary.add(it.uri) }
        val selectingFirstTrack = old.currentIndex < 0 && combined.isNotEmpty()
        if (selectingFirstTrack) requestedStartSample = 0
        mutableState.value = old.copy(
            playlist = combined,
            libraryTracks = library,
            currentIndex = if (selectingFirstTrack) 0 else old.currentIndex,
            progress = if (selectingFirstTrack) 0f else old.progress,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
        persistPlaylist()
        if (selectingFirstTrack) startPlayback()
    }

    fun importTracks(document: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val root = resolver.openInputStream(document)?.bufferedReader()?.use { JSONObject(it.readText()) }
                        ?: error("Could not read the selected file")
                    require(root.optString("format") == TRACKS_EXPORT_FORMAT) {
                        "This is not an EnCodec Player tracks export"
                    }
                    val entries = root.optJSONArray("tracks") ?: JSONArray()
                    buildList {
                        for (index in 0 until entries.length()) {
                            val entry = entries.optJSONObject(index) ?: continue
                            val uri = runCatching { Uri.parse(entry.optString("uri")) }.getOrNull() ?: continue
                            if (uri.scheme.equals("https", true)) {
                                runCatching {
                                    val prefix = HttpsStreams.readPrefix(uri.toString(), STATIC_HEADER_PREFIX_BYTES)
                                    remoteHeaderPrefixes[uri.toString()] = prefix
                                    ByteArrayInputStream(prefix).use(EcdcReader::inspect)
                                }.getOrNull()?.let { header ->
                                    runCatching { validateHeader(header) }.getOrNull()?.let {
                                        add(PlaylistItem(uri, entry.optString("title").ifBlank { remoteDisplayName(uri) }, header))
                                    }
                                }
                            } else if (uri.scheme.equals("content", true) || uri.scheme.equals("file", true)) {
                                runCatching {
                                    resolver.openInputStream(uri)?.use(EcdcReader::inspect)
                                        ?: error("File is unavailable")
                                }.getOrNull()?.let { header ->
                                    runCatching { validateHeader(header) }.getOrNull()?.let {
                                        add(PlaylistItem(uri, entry.optString("title").ifBlank { displayName(uri) }, header))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            result.onSuccess { imported ->
                val before = mutableState.value
                val known = before.libraryTracks.mapTo(mutableSetOf()) { it.uri.toString() }
                val additions = imported.filter { known.add(it.uri.toString()) }
                val combined = before.playlist + additions
                val first = before.currentIndex < 0 && combined.isNotEmpty()
                if (first) requestedStartSample = 0
                mutableState.value = before.copy(
                    playlist = combined,
                    libraryTracks = before.libraryTracks + additions,
                    currentIndex = if (first) 0 else before.currentIndex,
                    progress = if (first) 0f else before.progress,
                    error = if (additions.isEmpty()) "No available tracks were imported" else "Imported ${additions.size} track(s)",
                )
                additions.forEach { item ->
                    if (item.uri.scheme.equals("content", true)) runCatching {
                        getApplication<Application>().contentResolver.takePersistableUriPermission(
                            item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                persistPlaylist()
                if (first) startPlayback()
            }.onFailure {
                mutableState.value = mutableState.value.copy(error = "Import failed: ${it.message}")
            }
        }
    }

    fun importStreams(document: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val root = resolver.openInputStream(document)?.bufferedReader()?.use { JSONObject(it.readText()) }
                        ?: error("Could not read the selected file")
                    require(root.optString("format") == STREAMS_EXPORT_FORMAT) {
                        "This is not an EnCodec Player livestreams export"
                    }
                    val entries = root.optJSONArray("streams") ?: JSONArray()
                    buildList {
                        for (index in 0 until entries.length()) {
                            val item = entries.optJSONObject(index) ?: continue
                            val url = item.optString("manifestUrl").trim()
                            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: continue
                            if (uri.host.isNullOrBlank() || !(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) ||
                                !uri.path.orEmpty().endsWith(".json", true)
                            ) continue
                            val variant = item.optString("variant").takeIf(String::isNotBlank)
                                ?.let { runCatching { EncodecVariant.fromWireName(it) }.getOrNull() }
                            add(SavedLiveStream(
                                manifestUrl = uri.toString(),
                                title = item.optString("title").ifBlank { liveDisplayName(uri) },
                                variant = variant,
                                codebooks = item.optInt("codebooks", 0).takeIf { it > 0 },
                                bandwidthKbps = item.optDouble("bandwidthKbps", Double.NaN).takeIf { it.isFinite() },
                            ))
                        }
                    }
                }
            }
            result.onSuccess { imported ->
                val before = mutableState.value
                val known = before.livestreams.mapTo(mutableSetOf()) { it.manifestUrl }
                val additions = imported.filter { known.add(it.manifestUrl) }
                mutableState.value = before.copy(
                    livestreams = before.livestreams + additions,
                    error = if (additions.isEmpty()) "No new livestreams were imported" else "Imported ${additions.size} livestream(s)",
                )
                persistPlaylist()
            }.onFailure {
                mutableState.value = mutableState.value.copy(error = "Import failed: ${it.message}")
            }
        }
    }

    fun exportTracks(destination: Uri) = exportLibrary(destination, TRACKS_EXPORT_FORMAT) { state ->
        JSONObject().put("format", TRACKS_EXPORT_FORMAT).put("version", 1).put("tracks", JSONArray().apply {
            state.libraryTracks.forEach { put(JSONObject().put("uri", it.uri.toString()).put("title", it.title)) }
        })
    }

    fun exportStreams(destination: Uri) = exportLibrary(destination, STREAMS_EXPORT_FORMAT) { state ->
        JSONObject().put("format", STREAMS_EXPORT_FORMAT).put("version", 1).put("streams", JSONArray().apply {
            state.livestreams.forEach { stream ->
                put(JSONObject().put("manifestUrl", stream.manifestUrl).put("title", stream.title).apply {
                    stream.variant?.let { put("variant", it.wireName) }
                    stream.codebooks?.let { put("codebooks", it) }
                    stream.bandwidthKbps?.let { put("bandwidthKbps", it) }
                })
            }
        })
    }

    private fun exportLibrary(destination: Uri, format: String, content: (PlayerState) -> JSONObject) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = getApplication<Application>().contentResolver.openOutputStream(destination)
                        ?: error("Could not create the selected file")
                    output.bufferedWriter().use { it.write(content(mutableState.value).toString(2)) }
                }
            }.onSuccess {
                mutableState.value = mutableState.value.copy(error = "Exported ${if (format == TRACKS_EXPORT_FORMAT) "tracks" else "livestreams"}")
            }.onFailure {
                mutableState.value = mutableState.value.copy(error = "Export failed: ${it.message}")
            }
        }
    }

    fun addUrl(rawUrl: String) {
        if (mutableState.value.addingUrl) return
        val text = rawUrl.trim()
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (
            uri == null ||
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank()
        ) {
            mutableState.value = mutableState.value.copy(error = "Enter a valid HTTPS URL")
            return
        }
        if (mutableState.value.libraryTracks.any { it.uri == uri }) {
            mutableState.value = mutableState.value.copy(error = "That URL is already in your tracks")
            return
        }

        mutableState.value = mutableState.value.copy(addingUrl = true, error = null)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val prefix = HttpsStreams.readPrefix(
                        uri.toString(), STATIC_HEADER_PREFIX_BYTES,
                    ).also { remoteHeaderPrefixes[uri.toString()] = it }
                    ByteArrayInputStream(prefix).use { input ->
                        val header = EcdcReader.inspect(input)
                        validateHeader(header)
                        PlaylistItem(uri, remoteDisplayName(uri), header)
                    }
                }
            }
            val old = mutableState.value
            result.onSuccess { item ->
                if (old.libraryTracks.any { it.uri == item.uri }) {
                    mutableState.value = old.copy(addingUrl = false)
                } else {
                    val combined = old.playlist + item
                    val library = old.libraryTracks + item
                    val selectingFirstTrack = old.currentIndex < 0
                    if (selectingFirstTrack) requestedStartSample = 0
                    mutableState.value = old.copy(
                        playlist = combined,
                        libraryTracks = library,
                        currentIndex = if (selectingFirstTrack) 0 else old.currentIndex,
                        progress = if (selectingFirstTrack) 0f else old.progress,
                        addingUrl = false,
                        error = null,
                    )
                    persistPlaylist()
                    if (selectingFirstTrack) startPlayback()
                }
            }.onFailure { error ->
                mutableState.value = old.copy(
                    addingUrl = false,
                    error = "Could not add URL: ${error.message ?: "network error"}",
                )
            }
        }
    }

    /** Select finite ECDC playback or EnCodec Live from the URL path. */
    fun openUrl(rawUrl: String) {
        val text = rawUrl.trim()
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri == null ||
            !(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) ||
            uri.host.isNullOrBlank()
        ) {
            mutableState.value = mutableState.value.copy(
                error = "Enter a valid HTTP or HTTPS URL",
            )
            return
        }
        when {
            uri.path.orEmpty().endsWith(".ecdc", ignoreCase = true) -> addUrl(text)
            uri.path.orEmpty().endsWith(".json", ignoreCase = true) -> openLive(text)
            else -> mutableState.value = mutableState.value.copy(
                error = "The URL must end in .ecdc for a file or .json for a livestream",
            )
        }
    }

    fun lastLiveUrl(): String = playlistStore.loadLastLiveUrl()

    fun openLive(rawUrl: String, station: RadioStation? = null) {
        val text = rawUrl.trim()
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri == null ||
            !(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) ||
            uri.host.isNullOrBlank()
        ) {
            mutableState.value = mutableState.value.copy(
                error = "Enter a valid HTTP or HTTPS live manifest URL",
            )
            return
        }
        requestedStartSample = 0
        stopInternal(resetProgress = true)
        playlistStore.saveLastLiveUrl(uri.toString())
        val snapshot = mutableState.value
        val stream = snapshot.livestreams.firstOrNull { it.manifestUrl == uri.toString() }
            ?: SavedLiveStream(uri.toString(), liveDisplayName(uri))
        val savedStreams = if (snapshot.livestreams.any { it.manifestUrl == stream.manifestUrl }) {
            snapshot.livestreams
        } else {
            snapshot.livestreams + stream
        }
        mutableState.value = snapshot.copy(
            livestreams = savedStreams,
            recentItems = recentKey("stream", uri.toString(), snapshot.recentItems),
            currentStationId = station?.id ?: snapshot.currentStationId,
            live = LiveUiState(
                manifestUrl = stream.manifestUrl,
                title = station?.name ?: stream.title,
                variant = stream.variant,
                codebooks = stream.codebooks,
                bandwidthKbps = stream.bandwidthKbps,
            ),
            playing = false,
            paused = false,
            progress = 0f,
            error = null,
        )
        persistPlaylist()
        startLivePlayback()
    }

    fun removeLiveStream(index: Int) {
        val snapshot = mutableState.value
        if (index !in snapshot.livestreams.indices) return
        mutableState.value = snapshot.copy(
            livestreams = snapshot.livestreams.toMutableList().apply { removeAt(index) },
            recentItems = snapshot.recentItems.filterNot { it == "stream:${snapshot.livestreams[index].manifestUrl}" },
        )
        persistPlaylist()
    }

    fun moveLiveStream(fromIndex: Int, toIndex: Int) {
        val snapshot = mutableState.value
        if (fromIndex !in snapshot.livestreams.indices || toIndex !in snapshot.livestreams.indices || fromIndex == toIndex) return
        val reordered = snapshot.livestreams.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        mutableState.value = snapshot.copy(livestreams = reordered)
        persistPlaylist()
    }

    fun clearLiveStreams() {
        if (mutableState.value.livestreams.isEmpty()) return
        mutableState.value = mutableState.value.copy(
            livestreams = emptyList(),
            recentItems = mutableState.value.recentItems.filterNot { it.startsWith("stream:") },
        )
        persistPlaylist()
    }

    fun disconnectLive() {
        if (mutableState.value.live == null) return
        stopInternal(resetProgress = true)
        mutableState.value = mutableState.value.copy(live = null, error = null)
        stopPlaybackService()
    }

    fun reconnectLive() = restartLive("Reconnecting…")

    fun jumpToLive() = restartLive("Jumping to live edge…")

    private fun restartLive(status: String) {
        if (mutableState.value.live == null) return
        stopInternal(resetProgress = false)
        mutableState.value = mutableState.value.copy(
            live = mutableState.value.live?.copy(
                status = status,
                sequence = null,
                bufferedSegments = 0,
                buffering = true,
            ),
            error = null,
        )
        startLivePlayback()
    }

    fun selectTrack(index: Int) {
        if (index !in mutableState.value.playlist.indices) return
        requestedStartSample = 0
        stopInternal(resetProgress = true)
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(currentIndex = index, live = null)
        persistPlaylist()
        startPlayback()
    }

    /** Play a library item, adding it to the queue if it is not there yet. */
    fun playLibraryTrack(item: PlaylistItem) {
        val snapshot = mutableState.value
        val queueIndex = snapshot.playlist.indexOfFirst { it.uri == item.uri }
        if (queueIndex >= 0) {
            selectTrack(queueIndex)
            return
        }
        stopInternal(resetProgress = true)
        // Choosing a library track means “play this instead,” not “enqueue it.”
        // Keep any explicitly queued following tracks in their existing order.
        val replacement = replaceCurrentQueueItem(snapshot.playlist, snapshot.currentIndex, item)
        mutableState.value = snapshot.copy(
            playlist = replacement.items,
            currentIndex = replacement.currentIndex,
            live = null,
        )
        replacement.replacedItem?.let { previous ->
            if (mutableState.value.libraryTracks.none { it.uri == previous.uri } &&
                mutableState.value.playlist.none { it.uri == previous.uri }) {
                releaseLocalPermission(previous)
            }
        }
        requestedStartSample = 0
        persistPlaylist()
        startPlayback()
    }

    fun addLibraryTrackToQueue(item: PlaylistItem) {
        val snapshot = mutableState.value
        if (snapshot.playlist.any { it.uri == item.uri }) {
            mutableState.value = snapshot.copy(error = "That track is already in the queue")
            return
        }
        val queue = snapshot.playlist + item
        val needsQueueSelection = snapshot.currentIndex < 0
        val startNow = needsQueueSelection && snapshot.live == null
        if (startNow) {
            stopInternal(resetProgress = true)
            requestedStartSample = 0
        }
        mutableState.value = snapshot.copy(
            playlist = queue,
            currentIndex = if (needsQueueSelection) 0 else snapshot.currentIndex,
            progress = if (needsQueueSelection) 0f else snapshot.progress,
            live = if (startNow) null else snapshot.live,
        )
        persistPlaylist()
        if (startNow) startPlayback()
    }

    fun deleteLibraryTrack(item: PlaylistItem) {
        val snapshot = mutableState.value
        val library = snapshot.libraryTracks.filterNot { it.uri == item.uri }
        val queueIndex = snapshot.playlist.indexOfFirst { it.uri == item.uri }
        mutableState.value = snapshot.copy(libraryTracks = library)
        if (queueIndex >= 0) removeTrack(queueIndex)
        val updated = mutableState.value
        mutableState.value = updated.copy(
            libraryTracks = library,
            recentItems = updated.recentItems.filterNot { it == "track:${item.uri}" },
        )
        if (library.none { it.uri == item.uri } && mutableState.value.playlist.none { it.uri == item.uri }) {
            releaseLocalPermission(item)
        }
        persistPlaylist()
    }

    fun playPause() {
        val snapshot = mutableState.value
        when {
            !snapshot.playing && snapshot.live != null && snapshot.currentStationId != null -> startLivePlayback()
            !snapshot.playing -> startPlayback()
            snapshot.paused -> play()
            else -> pause()
        }
    }

    fun play() {
        val snapshot = mutableState.value
        when {
            !snapshot.playing && snapshot.live != null && snapshot.currentStationId != null -> startLivePlayback()
            !snapshot.playing -> startPlayback()
            snapshot.paused -> {
                session?.resume()
            liveSession?.resume()
                mutableState.value = snapshot.copy(paused = false)
                setPlaybackWakeActive(true)
            }
        }
    }

    fun pause() {
        val snapshot = mutableState.value
        if (snapshot.playing && !snapshot.paused) {
            session?.pause()
            liveSession?.pause()
            mutableState.value = snapshot.copy(paused = true)
            setPlaybackWakeActive(false)
        }
    }

    fun stop() {
        val wasLive = mutableState.value.live != null
        requestedStartSample = 0
        stopInternal(resetProgress = true)
        if (wasLive) {
            mutableState.value = mutableState.value.copy(
                live = mutableState.value.live?.copy(status = "Stopped"),
            )
        }
        stopPlaybackService()
    }

    fun next() {
        val snapshot = mutableState.value
        if (snapshot.live != null) {
            stepCatalogStation(1)
            return
        }
        if (snapshot.playlist.isEmpty()) return
        val nextIndex = when {
            snapshot.shuffle -> nextShuffleIndex(snapshot, restartCycle = true)
            snapshot.currentIndex < snapshot.playlist.lastIndex -> snapshot.currentIndex + 1
            snapshot.repeatMode == RepeatMode.LIST -> 0
            else -> null
        } ?: return
        requestedStartSample = 0
        moveAndPlay(nextIndex)
    }

    fun previous() {
        val snapshot = mutableState.value
        if (snapshot.live != null) {
            stepCatalogStation(-1)
            return
        }
        if (snapshot.playlist.isEmpty()) return
        val positionSeconds = snapshot.current?.let {
            it.header.audioLengthSamples * snapshot.progress / it.header.variant.sampleRate
        } ?: 0f
        if (positionSeconds >= 3f) {
            seekToFraction(0f)
            return
        }
        requestedStartSample = 0
        val previousIndex = when {
            snapshot.currentIndex > 0 -> snapshot.currentIndex - 1
            snapshot.repeatMode == RepeatMode.LIST -> snapshot.playlist.lastIndex
            else -> 0
        }
        moveAndPlay(previousIndex)
    }

    fun toggleShuffle() {
        val snapshot = mutableState.value
        if (snapshot.live != null) return
        val enabled = !snapshot.shuffle
        shufflePlayedUris.clear()
        if (enabled) snapshot.current?.let { shufflePlayedUris += it.uri.toString() }
        mutableState.value = snapshot.copy(shuffle = enabled)
        persistPlaylist()
    }

    fun cycleRepeatMode() {
        val snapshot = mutableState.value
        if (snapshot.live != null) return
        mutableState.value = snapshot.copy(repeatMode = snapshot.repeatMode.next())
        persistPlaylist()
    }

    fun toggleDiagnostics() {
        val enabled = !mutableState.value.diagnosticsEnabled
        LiveDiagnostics.enabled = enabled
        mutableState.value = mutableState.value.copy(diagnosticsEnabled = enabled)
        persistPlaylist()
    }

    fun setRescaleEnabled(enabled: Boolean) {
        val snapshot = mutableState.value
        if (snapshot.rescaleEnabled == enabled) return
        mutableState.value = snapshot.copy(rescaleEnabled = enabled)
        persistPlaylist()
        if (snapshot.playing) {
            if (snapshot.live != null) {
                startLivePlayback()
            } else {
                requestedStartSample = snapshot.current?.let {
                    (it.header.audioLengthSamples * snapshot.progress).toLong()
                } ?: 0L
                startPlayback(startPaused = snapshot.paused)
            }
        }
    }

    fun setExperimentalVocos(enabled: Boolean) {
        val snapshot = mutableState.value
        if (snapshot.experimentalVocos == enabled) return
        mutableState.value = snapshot.copy(experimentalVocos = enabled)
        persistPlaylist()
        if (snapshot.playing) {
            if (snapshot.live != null) {
                startLivePlayback()
            } else {
                requestedStartSample = snapshot.current?.let {
                    (it.header.audioLengthSamples * snapshot.progress).toLong()
                } ?: 0L
                startPlayback(startPaused = snapshot.paused)
            }
        }
    }

    fun seekToFraction(fraction: Float) {
        val snapshot = mutableState.value
        if (snapshot.live != null) return
        val current = snapshot.current ?: return
        val safeFraction = fraction.coerceIn(0f, 0.999999f)
        requestedStartSample = (current.header.audioLengthSamples * safeFraction).toLong()
        mutableState.value = snapshot.copy(progress = safeFraction)
        if (snapshot.playing) startPlayback(startPaused = snapshot.paused)
    }

    fun seekToMillis(positionMillis: Long) {
        val current = mutableState.value.current ?: return
        val durationMillis = current.header.audioLengthSamples * 1_000L /
            current.header.variant.sampleRate
        if (durationMillis > 0) {
            seekToFraction(positionMillis.toFloat() / durationMillis)
        }
    }

    fun clearPlaylist() {
        val snapshot = mutableState.value
        shufflePlayedUris.clear()
        if (snapshot.live == null) {
            requestedStartSample = 0
            stopInternal(resetProgress = true)
            stopPlaybackService()
        }
        mutableState.value = snapshot.copy(
            playlist = emptyList(),
            currentIndex = -1,
            playing = if (snapshot.live != null) snapshot.playing else false,
            paused = if (snapshot.live != null) snapshot.paused else false,
            progress = if (snapshot.live != null) snapshot.progress else 0f,
            live = snapshot.live,
        )
        persistPlaylist()
    }

    fun removeTrack(index: Int) {
        val snapshot = mutableState.value
        val removed = snapshot.playlist.getOrNull(index) ?: return
        shufflePlayedUris -= removed.uri.toString()
        if (snapshot.live != null) {
            val remaining = snapshot.playlist.toMutableList().apply { removeAt(index) }
            val currentUri = snapshot.current?.uri
            val currentIndex = remaining.indexOfFirst { it.uri == currentUri }.takeIf { it >= 0 }
                ?: if (remaining.isEmpty()) -1 else index.coerceAtMost(remaining.lastIndex)
            mutableState.value = snapshot.copy(playlist = remaining, currentIndex = currentIndex)
            if (mutableState.value.libraryTracks.none { it.uri == removed.uri } &&
                remaining.none { it.uri == removed.uri }) releaseLocalPermission(removed)
            persistPlaylist()
            return
        }
        if (index != snapshot.currentIndex) {
            // Editing history or an upcoming row must not tear down the active
            // decoder/audio session. Only the queue indices need adjustment.
            val remaining = snapshot.playlist.toMutableList().apply { removeAt(index) }
            val currentIndex = when {
                snapshot.currentIndex !in snapshot.playlist.indices -> -1
                index < snapshot.currentIndex -> snapshot.currentIndex - 1
                else -> snapshot.currentIndex
            }
            mutableState.value = snapshot.copy(playlist = remaining, currentIndex = currentIndex)
            if (mutableState.value.libraryTracks.none { it.uri == removed.uri } &&
                remaining.none { it.uri == removed.uri }) releaseLocalPermission(removed)
            persistPlaylist()
            return
        }
        val wasPlaying = snapshot.playing
        val wasPaused = snapshot.paused

        if (wasPlaying) stopInternal(resetProgress = true)
        val remaining = snapshot.playlist.toMutableList().apply { removeAt(index) }
        val newIndex = when {
            remaining.isEmpty() -> -1
            else -> index.coerceAtMost(remaining.lastIndex)
        }
        val newProgress = 0f
        requestedStartSample = remaining.getOrNull(newIndex)?.let {
            (it.header.audioLengthSamples * newProgress).toLong()
        } ?: 0L
        mutableState.value = mutableState.value.copy(
            playlist = remaining,
            currentIndex = newIndex,
            playing = false,
            paused = false,
            progress = newProgress,
            recentItems = snapshot.recentItems,
        )
        persistPlaylist()
        if (wasPlaying && newIndex >= 0) {
            startPlayback(startPaused = wasPaused)
        } else if (newIndex < 0) {
            stopPlaybackService()
        }
        if (mutableState.value.libraryTracks.none { it.uri == removed.uri } &&
            mutableState.value.playlist.none { it.uri == removed.uri }) releaseLocalPermission(removed)
    }

    private fun moveAndPlay(index: Int) {
        stopInternal(resetProgress = true)
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(currentIndex = index, live = null)
        persistPlaylist()
        startPlayback()
    }

    fun moveTrack(fromIndex: Int, toIndex: Int) {
        val snapshot = mutableState.value
        if (fromIndex !in snapshot.playlist.indices || toIndex !in snapshot.playlist.indices || fromIndex == toIndex) return
        val firstReorderableIndex = (snapshot.currentIndex + 1).coerceAtLeast(0)
        if (fromIndex < firstReorderableIndex || toIndex < firstReorderableIndex) return
        val reordered = snapshot.playlist.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        mutableState.value = snapshot.copy(
            playlist = reordered,
        )
        persistPlaylist()
    }

    fun removeRecent(key: String) {
        val snapshot = mutableState.value
        if (key !in snapshot.recentItems) return
        mutableState.value = snapshot.copy(recentItems = snapshot.recentItems - key)
        persistPlaylist()
    }

    private fun stepSavedStream(delta: Int) {
        val snapshot = mutableState.value
        if (snapshot.livestreams.size < 2) return
        val currentUrl = snapshot.live?.manifestUrl ?: return
        val currentIndex = snapshot.livestreams.indexOfFirst { it.manifestUrl == currentUrl }
        if (currentIndex < 0) return
        val nextIndex = Math.floorMod(currentIndex + delta, snapshot.livestreams.size)
        openLive(snapshot.livestreams[nextIndex].manifestUrl)
    }

    private fun rememberRecentTrack(item: PlaylistItem) {
        val snapshot = mutableState.value
        val updated = recentKey("track", item.uri.toString(), snapshot.recentItems)
        if (updated != snapshot.recentItems) {
            mutableState.value = snapshot.copy(recentItems = updated)
            persistPlaylist()
        }
    }

    private fun startLivePlayback() {
        if (!mutableState.value.modelReady) return
        val live = mutableState.value.live ?: return
        stopInternal(resetProgress = false)
        ensurePlaybackService()
        val generation = playbackGeneration
        playbackJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                playing = true,
                paused = false,
                error = null,
                live = mutableState.value.live?.copy(
                    status = "Checking stream format…",
                    buffering = true,
                ),
            )
            try {
                decoderMutex.withLock {
                    coroutineScope {
                            val source = LiveStreamSource(
                                live.manifestUrl,
                                networkDispatcher = liveDownloadDispatcher,
                            )
                            val queue = Channel<DownloadedLiveSegment>(LIVE_PREFETCH_CAPACITY)
                            val buffered = AtomicInteger(0)
                            val targetBuffer = AtomicInteger(LIVE_REBUFFER_TARGET_SEGMENTS)
                            val rebufferEvents = AtomicInteger(0)
                            fun publishBuffer(
                                status: String? = null,
                                buffering: Boolean? = null,
                            ) {
                                if (playbackGeneration != generation) return
                                val depth = buffered.get()
                                mutableState.value = mutableState.value.copy(
                                    live = mutableState.value.live?.copy(
                                        status = status ?: if (depth > 0) "LIVE" else "Rebuffering…",
                                        bufferedSegments = depth,
                                        targetBufferedSegments = targetBuffer.get(),
                                        buffering = buffering ?: mutableState.value.live?.buffering ?: false,
                                    ),
                                )
                            }
                            val streamInit = source.initialize { status ->
                                publishBuffer(status, buffering = true)
                            }
                            require(streamInit.variant == EncodecVariant.MONO_24_KHZ) {
                                "This app supports only 24 kHz mono EnCodec radio streams"
                            }
                            require(streamInit.codebooks in setOf(2, 4, 8, 16)) {
                                "Vocos supports only 2, 4, 8, or 16 codebooks"
                            }
                            val manifestTitle = source.streamTitle
                            val initializedState = mutableState.value
                            val initializedLive = initializedState.live
                            mutableState.value = initializedState.copy(
                                live = initializedLive?.copy(
                                    title = manifestTitle ?: initializedLive.title,
                                    variant = streamInit.variant,
                                    codebooks = streamInit.codebooks,
                                    bandwidthKbps = streamInit.bandwidthKbps,
                                ),
                            )
                            val producer = launch(liveDownloadDispatcher) {
                                try {
                                    // Keep preparing manifest-listed segments in
                                    // the background until the queue is full.
                                    while (isActive) {
                                        val downloaded = source.nextSegment { status ->
                                            if (buffered.get() == 0) publishBuffer(status, buffering = true)
                                        }
                                        if (downloaded.downloadMillis >=
                                            (downloaded.durationSeconds * 1_000).toLong()
                                        ) {
                                            val previousTarget = targetBuffer.get()
                                            val newTarget = targetBuffer.updateAndGet { current ->
                                                (current + 1).coerceAtMost(LIVE_MAX_BUFFER_SEGMENTS)
                                            }
                                            if (newTarget != previousTarget) {
                                                LiveDiagnostics.warn(
                                                    "slow segment seq=${downloaded.sequence} " +
                                                        "downloadMs=${downloaded.downloadMillis} " +
                                                        "durationMs=" +
                                                        "${(downloaded.durationSeconds * 1_000).toLong()} " +
                                                        "newTarget=$newTarget",
                                                )
                                            }
                                        }
                                        buffered.incrementAndGet()
                                        try {
                                            queue.send(downloaded)
                                        } catch (error: Throwable) {
                                            buffered.decrementAndGet()
                                            throw error
                                        }
                                        LiveDiagnostics.info(
                                            "queue add seq=${downloaded.sequence} depth=${buffered.get()} " +
                                                "target=${targetBuffer.get()} capacity=$LIVE_PREFETCH_CAPACITY " +
                                                "manifestEdge=${downloaded.reachedManifestEdge}",
                                        )
                                        publishBuffer()
                                        if (downloaded.reachedManifestEdge) {
                                            // The manifest supplied a complete batch. Let that
                                            // buffered audio play before waking Wi-Fi or the
                                            // cellular modem for another manifest request.
                                            while (isActive && buffered.get() > targetBuffer.get()) {
                                                kotlinx.coroutines.delay(100)
                                            }
                                        }
                                    }
                                } finally {
                                    queue.close()
                                }
                            }
                            try {
                                val config = decoderConfig(streamInit.variant, streamInit.codebooks)
                                publishBuffer(
                                    if (config.vocos) "Loading Vocos decoder…" else "Loading EnCodec decoder…",
                                    buffering = true,
                                )
                                val decoderStarted = LiveDiagnostics.nowMs()
                                val (decoder, sink) = withContext(Dispatchers.IO) {
                                    decoderFor(config, streamInit.variant) to audioSink(streamInit.variant)
                                }
                                LiveDiagnostics.info(
                                    "decoder ready variant=${streamInit.variant.wireName} " +
                                        "elapsedMs=${LiveDiagnostics.nowMs() - decoderStarted}",
                                )
                                publishBuffer(
                                    if (config.vocos) "Vocos ready; buffering live audio…"
                                    else "EnCodec ready; buffering live audio…",
                                    buffering = true,
                                )
                                val livePlaybackLabel = if (config.vocos) "LIVE · Vocos" else "LIVE"
                                run {
                                    val newSession = LiveEcdcPlaybackSession(
                                        decoder,
                                        sink,
                                        diagnosticsEnabled = { mutableState.value.diagnosticsEnabled },
                                    )
                                    liveSession = newSession
                                    var deliveredSegments = 0
                                    var initialCushionFilled = false
                                    newSession.play(
                                        nextSegment = {
                                            if (deliveredSegments == 1 && !initialCushionFilled) {
                                                val requiredDepth = requiredLiveBufferDepth(
                                                    deliveredSegments,
                                                    targetBuffer.get(),
                                                )
                                                while (buffered.get() < requiredDepth &&
                                                    !producer.isCompleted
                                                ) {
                                                    publishBuffer(
                                                        "Preparing background queue " +
                                                            "${buffered.get()}/$requiredDepth…",
                                                        buffering = false,
                                                    )
                                                    kotlinx.coroutines.delay(25)
                                                }
                                                initialCushionFilled = true
                                            }
                                            if (buffered.get() == 0) {
                                                if (deliveredSegments > 0) {
                                                    val events = rebufferEvents.incrementAndGet()
                                                    if (events % REBUFFERS_PER_BUFFER_INCREASE == 0) {
                                                        targetBuffer.updateAndGet { current ->
                                                            (current + 1).coerceAtMost(LIVE_MAX_BUFFER_SEGMENTS)
                                                        }
                                                    }
                                                    LiveDiagnostics.warn(
                                                        "rebuffer event=$events delivered=$deliveredSegments " +
                                                            "newTarget=${targetBuffer.get()} producerDone=${producer.isCompleted}",
                                                    )
                                                }
                                                val requiredDepth = requiredLiveBufferDepth(
                                                    deliveredSegments,
                                                    targetBuffer.get(),
                                                )
                                                while (buffered.get() < requiredDepth &&
                                                    !producer.isCompleted
                                                ) {
                                                    val status = if (deliveredSegments == 0) {
                                                        "Waiting for first live segment…"
                                                    } else {
                                                        "Rebuffering ${buffered.get()}/$requiredDepth segments…"
                                                    }
                                                    publishBuffer(status, buffering = true)
                                                    kotlinx.coroutines.delay(50)
                                                }
                                            }
                                            val receiveStarted = LiveDiagnostics.nowMs()
                                            val downloaded = queue.receive()
                                            val receiveWaitMs = LiveDiagnostics.nowMs() - receiveStarted
                                            buffered.decrementAndGet()
                                            deliveredSegments++
                                            LiveDiagnostics.info(
                                                "queue take seq=${downloaded.sequence} depth=${buffered.get()} " +
                                                    "waitMs=$receiveWaitMs delivered=$deliveredSegments",
                                            )
                                            publishBuffer(
                                                "Decoding segment ${downloaded.sequence}…",
                                                buffering = false,
                                            )
                                            mutableState.value = mutableState.value.copy(
                                                live = mutableState.value.live?.copy(
                                                    codebooks = downloaded.codebooks,
                                                    bandwidthKbps = downloaded.bandwidthKbps,
                                                ),
                                            )
                                            LiveEcdcSegment(
                                                ByteArrayInputStream(downloaded.bytes),
                                                downloaded.sequence,
                                                downloaded.discontinuity,
                                            )
                                        },
                                        onSegmentPlaying = { sequence ->
                                            if (playbackGeneration == generation) {
                                                mutableState.value = mutableState.value.copy(
                                                    live = mutableState.value.live?.copy(
                                                    status = livePlaybackLabel,
                                                        sequence = sequence,
                                                        bufferedSegments = buffered.get(),
                                                        targetBufferedSegments = targetBuffer.get(),
                                                        buffering = false,
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }
                            } finally {
                                producer.cancelAndJoin()
                                queue.cancel()
                            }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: LiveInitializationChangedException) {
                if (playbackGeneration == generation) {
                    mutableState.value = mutableState.value.copy(
                        live = mutableState.value.live?.copy(
                            status = "Stream format changed; reconnecting…",
                            variant = changed.current.variant,
                            codebooks = changed.current.codebooks,
                            bandwidthKbps = changed.current.bandwidthKbps,
                            bufferedSegments = 0,
                            buffering = true,
                        ),
                    )
                    viewModelScope.launch {
                        kotlinx.coroutines.yield()
                        if (playbackGeneration == generation && mutableState.value.live != null) {
                            startLivePlayback()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (playbackGeneration == generation) {
                    mutableState.value = mutableState.value.copy(
                        error = "Live playback failed: ${error.message ?: "unknown error"}",
                        live = mutableState.value.live?.copy(status = "Disconnected"),
                    )
                }
            } finally {
                if (playbackGeneration == generation) {
                    liveSession = null
                    mutableState.value = mutableState.value.copy(playing = false, paused = false)
                    stopPlaybackService()
                }
            }
        }
    }

    private fun startPlayback(startPaused: Boolean = false) {
        val selected = mutableState.value.current ?: return
        if (mutableState.value.live != null) {
            mutableState.value = mutableState.value.copy(live = null)
        }
        rememberRecentTrack(selected)
        if (mutableState.value.shuffle) shufflePlayedUris += selected.uri.toString()
        if (requestedStartSample >= selected.header.audioLengthSamples - 1) {
            requestedStartSample = 0
        }
        stopInternal(resetProgress = false)
        ensurePlaybackService()
        val generation = playbackGeneration
        val initialStartSample = requestedStartSample
        playbackJob = viewModelScope.launch {
            val playbackScope = this
            val initial = mutableState.value
            val initialDuration = initial.current?.header?.audioLengthSamples ?: 1L
            mutableState.value = mutableState.value.copy(
                playing = true,
                paused = startPaused,
                progress = (initialStartSample.toDouble() / initialDuration)
                    .toFloat().coerceIn(0f, 1f),
                error = null,
            )
            try {
                decoderMutex.withLock {
                    var firstIteration = true
                    while (isActive) {
                        val snapshot = mutableState.value
                        val item = snapshot.current ?: break
                        val trackStartSample = if (firstIteration) {
                            initialStartSample
                        } else {
                            0L
                        }
                        firstIteration = false
                        val config = decoderConfig(item.header.variant, item.header.numCodebooks)
                        val preparedRemote = if (item.uri.scheme.equals("https", true)) {
                            playbackScope.async(Dispatchers.IO) {
                                prepareRemoteInput(
                                    playbackScope, item.uri, item.header, trackStartSample,
                                )
                            }
                        } else {
                            null
                        }
                        val (decoder, sink) = withContext(Dispatchers.IO) {
                            decoderFor(config, item.header.variant) to
                                audioSink(item.header.variant)
                        }
                        run {
                            val newSession = EcdcPlaybackSession(
                                decoder,
                                sink,
                            )
                            session = newSession
                            if (mutableState.value.paused) newSession.pause()
                            withPlaybackInput(
                                item.uri,
                                item.header,
                                trackStartSample,
                                preparedRemote,
                            ) {
                                    input, initialFrameIndex ->
                                newSession.play(
                                    input = input,
                                    startSample = trackStartSample,
                                    initialFrameIndex = initialFrameIndex,
                                    onProgress = { progress ->
                                        if (playbackGeneration == generation) {
                                            requestedStartSample =
                                                (item.header.audioLengthSamples * progress).toLong()
                                            mutableState.value = mutableState.value.copy(progress = progress)
                                        }
                                    },
                                )
                            }
                        }
                        if (playbackGeneration != generation) break
                        val nextIndex = nextIndexAfterCompletion(mutableState.value) ?: break
                        requestedStartSample = 0
                        val finishedState = mutableState.value
                        mutableState.value = finishedState.copy(
                            currentIndex = nextIndex,
                            progress = 0f,
                        )
                        mutableState.value.current?.let(::rememberRecentTrack)
                        persistPlaylist()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (playbackGeneration == generation) {
                    mutableState.value = mutableState.value.copy(
                        error = error.message ?: "Playback failed",
                    )
                }
            } finally {
                if (playbackGeneration == generation) {
                    session = null
                    mutableState.value = mutableState.value.copy(
                        playing = false,
                        paused = false,
                    )
                    stopPlaybackService()
                }
            }
        }
    }

    private fun stopInternal(resetProgress: Boolean) {
        playbackGeneration++
        session?.stop()
        liveSession?.stop()
        playbackJob?.cancel()
        playbackJob = null
        session = null
        liveSession = null
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(
            playing = false,
            paused = false,
            progress = if (resetProgress) 0f else snapshot.progress,
        )
    }

    private fun publishMediaState(state: PlayerState) {
        val current = state.current
        val live = state.live
        val station = state.catalog.firstOrNull { it.id == state.currentStationId }
        val durationMillis = if (live != null) 0L else current?.let {
            it.header.audioLengthSamples * 1_000L / it.header.variant.sampleRate
        } ?: 0L
        val positionMillis = if (live != null) 0L else (durationMillis * state.progress).toLong()
        val playbackState = when {
            state.playing && !state.paused -> PlaybackState.STATE_PLAYING
            state.playing -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val standardActions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP
        val finiteActions = PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        val liveStationActions = if (state.catalog.size > 1) {
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        } else {
            0L
        }
        val stateBuilder = PlaybackState.Builder().setActions(
            standardActions or if (live == null && station == null) finiteActions else liveStationActions,
        )
        mediaSession.setPlaybackState(
            stateBuilder
                .setState(
                    playbackState,
                    positionMillis,
                    if (playbackState == PlaybackState.STATE_PLAYING) 1f else 0f,
                )
                .build(),
        )
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, station?.name ?: live?.title ?: current?.title ?: "OndaBaja")
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    if (station != null) station.region ?: "Radio en vivo" else if (live != null) "LIVE EnCodec" else "EnCodec audio",
                )
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMillis)
                .build(),
        )
        mediaSession.isActive = current != null || live != null || station != null
        publishMediaNotification(state)
    }

    private fun mediaPublishKey(state: PlayerState): MediaPublishKey {
        val current = state.current
        val positionSecond = if (state.live == null && current != null) {
            val durationSeconds = current.header.audioLengthSamples.toDouble() /
                current.header.variant.sampleRate
            (durationSeconds * state.progress).toLong()
        } else {
            0L
        }
        val livePhase = state.live?.let { live ->
            when {
                !state.playing -> live.status
                live.buffering -> "buffering"
                else -> "live"
            }
        }
        return MediaPublishKey(
            currentUri = current?.uri?.toString(),
            liveUrl = state.live?.manifestUrl,
            playing = state.playing,
            paused = state.paused,
            shuffle = state.shuffle,
            repeatMode = state.repeatMode,
            positionSecond = positionSecond,
            livePhase = livePhase,
        )
    }

    private fun publishMediaNotification(state: PlayerState) {
        val manager = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val current = state.current
        val live = state.live
        val station = state.catalog.firstOrNull { it.id == state.currentStationId }
        if (!state.playing || (current == null && live == null && station == null)) {
            manager.cancel(MEDIA_NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    MEDIA_CHANNEL_ID,
                    "OndaBaja",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Controles de radio en vivo" },
            )
        }
        val stopLiveStream = live != null && state.playing && !state.paused
        val playPauseTitle = when {
            stopLiveStream -> "Stop"
            state.paused -> "Play"
            else -> "Pause"
        }
        val playPauseIcon = when {
            stopLiveStream -> R.drawable.ic_media_stop
            state.paused -> android.R.drawable.ic_media_play
            else -> android.R.drawable.ic_media_pause
        }
        val notificationBuilder = Notification.Builder(getApplication(), MEDIA_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(station?.name ?: live?.title ?: current?.title ?: "OndaBaja")
            .setContentText(if (station != null) "En vivo • ${station.region ?: "Chile"}" else live?.let { "LIVE • ${it.status}" } ?: "EnCodec audio")
            .setContentIntent(mediaIntent(MainActivity.ACTION_OPEN, 0))
            .setOnlyAlertOnce(true)
            .setOngoing(!state.paused)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (state.catalog.size > 1) {
            notificationBuilder.addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                mediaIntent(MainActivity.ACTION_PREVIOUS, 1),
            )
        }
        val playActionIndex = if (state.catalog.size > 1) 1 else 0
        notificationBuilder.addAction(
            playPauseIcon,
            playPauseTitle,
            mediaIntent(if (stopLiveStream) MainActivity.ACTION_STOP else MainActivity.ACTION_PLAY_PAUSE, 2),
        )
        if (state.catalog.size > 1) {
            notificationBuilder.addAction(
                android.R.drawable.ic_media_next,
                "Next",
                mediaIntent(MainActivity.ACTION_NEXT, 3),
            )
        }
        notificationBuilder.setStyle(
            Notification.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, playActionIndex, if (state.catalog.size > 1) 2 else playActionIndex),
        )
        manager.notify(MEDIA_NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun mediaIntent(action: String, requestCode: Int): PendingIntent {
        if (action == MainActivity.ACTION_OPEN) {
            val openIntent = Intent(getApplication(), MainActivity::class.java).apply {
                this.action = action
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                getApplication(),
                requestCode,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val commandIntent = Intent(getApplication(), MediaControlReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            getApplication(),
            requestCode,
            commandIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onCleared() {
        val runningPlayback = playbackJob
        stopInternal(resetProgress = false)
        val decoderToClose = cachedDecoder
        val sinkToClose = cachedAudioSink
        cachedDecoder = null
        cachedAudioSink = null
        if (runningPlayback == null) {
            sinkToClose?.close()
            decoderToClose?.close()
        } else {
            runningPlayback.invokeOnCompletion {
                sinkToClose?.close()
                decoderToClose?.close()
            }
        }
        stopPlaybackService()
        val manager = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(MEDIA_NOTIFICATION_ID)
        if (activeInstance?.get() === this) activeInstance = null
        mediaSession.release()
        liveDownloadDispatcher.close()
        super.onCleared()
    }

    companion object {
        private var activeInstance: WeakReference<PlayerViewModel>? = null
        private val LIVE_REBUFFER_TARGET_SEGMENTS = BuildConfig.LIVE_REBUFFER_TARGET_SEGMENTS
        private val LIVE_MAX_BUFFER_SEGMENTS = BuildConfig.LIVE_MAX_BUFFER_SEGMENTS
        const val MEDIA_CHANNEL_ID = "emergency_radio_playback"
        const val MEDIA_NOTIFICATION_ID = 48
        private const val MEDIA_ACTION_JUMP_TO_LIVE =
            "com.henry.encodec.player.JUMP_TO_LIVE"
        // The current segment is already in the decoder/AudioTrack, so three
        // compressed successors provide a four-segment total cushion.
        private val LIVE_PREFETCH_CAPACITY = LIVE_MAX_BUFFER_SEGMENTS
        private const val REBUFFERS_PER_BUFFER_INCREASE = 1
        private const val STATIC_HEADER_PREFIX_BYTES = 1024
        private const val TRACKS_EXPORT_FORMAT = "encodec-player-tracks-v1"
        private const val STREAMS_EXPORT_FORMAT = "encodec-player-streams-v1"
        private const val STATIC_STARTUP_SECONDS = 1
        private const val STATIC_MIN_STARTUP_BYTES = 512
        private const val STATIC_MAX_STARTUP_BYTES = 8 * 1024

        internal fun dispatchMediaAction(action: String?) {
            val player = activeInstance?.get() ?: return
            when (action) {
                MainActivity.ACTION_PLAY_PAUSE -> player.playPause()
                MainActivity.ACTION_PREVIOUS -> player.previous()
                MainActivity.ACTION_NEXT -> player.next()
                MainActivity.ACTION_STOP -> player.stop()
                MainActivity.ACTION_JUMP_LIVE -> player.jumpToLive()
            }
        }

        internal fun refreshMediaState() {
            activeInstance?.get()?.let { it.publishMediaState(it.mutableState.value) }
        }
    }

    private fun nextIndexAfterCompletion(state: PlayerState): Int? {
        if (state.playlist.isEmpty()) return null
        if (state.repeatMode == RepeatMode.TRACK) return state.currentIndex
        if (!state.shuffle) {
            if (state.currentIndex < state.playlist.lastIndex) return state.currentIndex + 1
            return if (state.repeatMode == RepeatMode.LIST) 0 else null
        }
        return nextShuffleIndex(
            state,
            restartCycle = state.repeatMode == RepeatMode.LIST,
        )
    }

    private fun nextShuffleIndex(state: PlayerState, restartCycle: Boolean): Int? {
        state.current?.let { shufflePlayedUris += it.uri.toString() }
        val unplayed = state.playlist.indices.filter { index ->
            state.playlist[index].uri.toString() !in shufflePlayedUris
        }
        if (unplayed.isNotEmpty()) return unplayed.random()
        if (!restartCycle) return null

        val currentUri = state.current?.uri?.toString()
        shufflePlayedUris.clear()
        if (currentUri != null) shufflePlayedUris += currentUri
        return state.playlist.indices
            .filter { state.playlist[it].uri.toString() !in shufflePlayedUris }
            .randomOrNull()
            ?: state.currentIndex.takeIf { it in state.playlist.indices }
    }

    private fun ensurePlaybackService() {
        setPlaybackWakeActive(true)
    }

    private fun setPlaybackWakeActive(active: Boolean) {
        val application = getApplication<Application>()
        val intent = Intent(application, PlaybackService::class.java)
            .putExtra(PlaybackService.EXTRA_PLAYBACK_ACTIVE, active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
    }

    private fun stopPlaybackService() {
        val application = getApplication<Application>()
        application.stopService(
            Intent(getApplication(), PlaybackService::class.java),
        )
        (application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(MEDIA_NOTIFICATION_ID)
    }

    private fun clearStalePlaybackService() {
        val application = getApplication<Application>()
        application.stopService(Intent(application, PlaybackService::class.java))
        (application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(MEDIA_NOTIFICATION_ID)
    }

    private fun persistPlaylist() = playlistStore.save(mutableState.value)

    private fun recentKey(kind: String, value: String, existing: List<String>): List<String> =
        (listOf("$kind:$value") + existing.filterNot { it == "$kind:$value" }).take(20)

    private fun releaseLocalPermission(item: PlaylistItem) {
        if (!item.uri.scheme.equals("content", ignoreCase = true)) return
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                item.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "EnCodec track"
    }

    private fun remoteDisplayName(uri: Uri): String {
        val pathName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return Uri.decode(pathName ?: uri.host ?: "HTTPS stream")
    }

    private fun liveDisplayName(uri: Uri): String {
        val host = Uri.decode(uri.host ?: "EnCodec live")
        val streamName = uri.pathSegments
            .dropLast(1)
            .lastOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::decode)
        return streamName?.let { "$host • $it" } ?: host
    }

    private fun openInput(uri: Uri): InputStream =
        if (uri.scheme.equals("https", ignoreCase = true)) {
            HttpsStreams.open(uri.toString())
        } else {
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open the selected file")
        }

    private suspend fun <T> withPlaybackInput(
        uri: Uri,
        header: EcdcHeader,
        startSample: Long,
        preparedRemote: Deferred<PreparedRemoteInput>?,
        block: suspend (InputStream, Int) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (uri.scheme.equals("https", ignoreCase = true)) {
            val prepared = requireNotNull(preparedRemote).await()
            prepared.input.use { block(it, prepared.initialFrameIndex) }
        } else {
            prepareLocalInput(uri, header, startSample).let { prepared ->
                prepared.input.use { block(it, prepared.initialFrameIndex) }
            }
        }
    }

    private fun prepareLocalInput(
        uri: Uri,
        header: EcdcHeader,
        startSample: Long,
    ): PreparedRemoteInput {
        val frameIndex = if (header.variant == EncodecVariant.MONO_24_KHZ) {
            (startSample / EcdcReader.MONO_CHUNK_SAMPLES).toInt()
        } else {
            val stride = requireNotNull(header.variant.segmentStrideSamples)
            (startSample / stride).toInt()
        }
        if (frameIndex == 0) return PreparedRemoteInput(openInput(uri), 0)

        // Android document providers commonly expose a seekable descriptor. Use
        // it to jump directly to the requested ECDC frame. Cloud-backed and
        // virtual providers can expose a pipe instead, so retain the old reader
        // walk as a compatibility fallback.
        val headerBytes = openInput(uri).use(EcdcReader::readHeaderBytes)
        val initialFrameIndex = if (header.variant == EncodecVariant.MONO_24_KHZ) {
            // Decode one preceding four-second chunk so EcdcReader can retain
            // the final causal second as decoder warm-up for the target chunk.
            (frameIndex - 1).coerceAtLeast(0)
        } else {
            frameIndex
        }
        val rangeStart = if (header.variant == EncodecVariant.MONO_24_KHZ) {
            EcdcReader.monoChunkByteOffset(header, headerBytes.size, initialFrameIndex)
        } else {
            EcdcReader.frameByteOffset(header, headerBytes.size, initialFrameIndex)
        }
        val descriptor = getApplication<Application>().contentResolver
            .openAssetFileDescriptor(uri, "r")
            ?: return PreparedRemoteInput(openInput(uri), 0)
        return try {
            val positioned = descriptor.createInputStream()
            positioned.channel.position(descriptor.startOffset + rangeStart)
            PreparedRemoteInput(
                SequenceInputStream(
                    ByteArrayInputStream(headerBytes),
                    DescriptorInputStream(positioned, descriptor),
                ),
                initialFrameIndex,
            )
        } catch (_: Throwable) {
            runCatching { descriptor.close() }
            PreparedRemoteInput(openInput(uri), 0)
        }
    }

    private suspend fun prepareRemoteInput(
        scope: CoroutineScope,
        uri: Uri,
        header: EcdcHeader,
        startSample: Long,
    ): PreparedRemoteInput {
        val prefix = remoteHeaderPrefixes[uri.toString()] ?: HttpsStreams.readPrefix(
            uri.toString(), STATIC_HEADER_PREFIX_BYTES,
        ).also { remoteHeaderPrefixes[uri.toString()] = it }
        require(prefix.size >= 9) { "Remote ECDC header is incomplete" }
        val metadataSize = ((prefix[5].toInt() and 0xff) shl 24) or
            ((prefix[6].toInt() and 0xff) shl 16) or
            ((prefix[7].toInt() and 0xff) shl 8) or (prefix[8].toInt() and 0xff)
        val headerSize = 9 + metadataSize
        require(headerSize in 9..prefix.size) { "Remote ECDC metadata exceeds 1 KiB" }
        val exactHeader = prefix.copyOf(headerSize)
        val frameIndex = if (header.variant == EncodecVariant.MONO_24_KHZ) {
            (startSample / EcdcReader.MONO_CHUNK_SAMPLES).toInt()
        } else {
            val stride = requireNotNull(header.variant.segmentStrideSamples)
            (startSample / stride).toInt()
        }
        val initialFrameIndex = if (header.variant == EncodecVariant.MONO_24_KHZ) {
            (frameIndex - 1).coerceAtLeast(0)
        } else {
            frameIndex
        }
        val rangeStart = if (header.variant == EncodecVariant.MONO_24_KHZ) {
            EcdcReader.monoChunkByteOffset(header, headerSize, initialFrameIndex)
        } else {
            EcdcReader.frameByteOffset(header, headerSize, initialFrameIndex)
        }
        val startupBytes = (header.nominalBitrateBps * STATIC_STARTUP_SECONDS / 8)
            .coerceIn(STATIC_MIN_STARTUP_BYTES, STATIC_MAX_STARTUP_BYTES) + headerSize
        val input = DownloadAheadInputStream.open(
            scope = scope,
            startupBytes = startupBytes,
            sourceProvider = {
                SequenceInputStream(
                    ByteArrayInputStream(exactHeader),
                    HttpsStreams.openRange(uri.toString(), rangeStart),
                )
            },
        )
        return PreparedRemoteInput(input, initialFrameIndex)
    }

    private data class PreparedRemoteInput(
        val input: InputStream,
        val initialFrameIndex: Int,
    )

    private class DescriptorInputStream(
        source: FileInputStream,
        private val descriptor: AssetFileDescriptor,
    ) : FilterInputStream(source) {
        override fun close() {
            try {
                super.close()
            } finally {
                runCatching { descriptor.close() }
            }
        }
    }

    private fun validateHeader(header: EcdcHeader) {
        require(!header.usesLanguageModel) {
            "LM-coded files are not supported yet"
        }
        require(header.variant == EncodecVariant.MONO_24_KHZ) {
            "Emergency Radio accepts only 24 kHz mono EnCodec streams"
        }
        require(header.numCodebooks in setOf(2, 4, 8, 16)) {
            "Vocos supports only 2, 4, 8, or 16 codebooks"
        }
    }

    private fun decoderConfig(variant: EncodecVariant, codebooks: Int): DecoderConfig {
        require(variant == EncodecVariant.MONO_24_KHZ) { "Emergency Radio accepts only 24 kHz mono EnCodec streams" }
        require(codebooks in setOf(2, 4, 8, 16)) { "Vocos supports only 2, 4, 8, or 16 codebooks" }
        return DecoderConfig(
            assetName = "vocos-encodec-24khz-f32.bin",
            vocos = true,
            rescale = mutableState.value.rescaleEnabled,
        )
    }

    private data class DecoderConfig(
        val assetName: String,
        val vocos: Boolean = false,
        val rescale: Boolean = false,
    )

    /** Keep only one native model resident; variant changes trade a reload for much lower RAM use. */
    private fun decoderFor(
        config: DecoderConfig,
        variant: EncodecVariant,
    ): EncodecDecoder {
        cachedDecoder?.takeIf {
            it.variant == variant && it is VocosDecoder && it.rescale == config.rescale
        }?.let { return it }
        cachedDecoder?.close()
        cachedDecoder = null
        val decoder = VocosDecoder(
            installVocosModel(),
            getApplication<Application>(),
            rescale = config.rescale,
            diagnosticsEnabled = { mutableState.value.diagnosticsEnabled },
        )
        return decoder.also { cachedDecoder = it }
    }

    private fun audioSink(variant: EncodecVariant): AudioTrackSink {
        cachedAudioSink?.takeIf {
            it.sampleRate == variant.sampleRate && it.channels == variant.channels
        }?.let { return it }
        cachedAudioSink?.close()
        cachedAudioSink = null
        return AudioTrackSink(
            variant.sampleRate,
            variant.channels,
            diagnosticsEnabled = { mutableState.value.diagnosticsEnabled },
        ).also { cachedAudioSink = it }
    }

    private fun installVocosModel(): File {
        val name = BuildConfig.DECODER_MODEL_ASSET
        val filesDir = getApplication<Application>().filesDir
        val destination = File(filesDir, name)
        if (destination.takeIf(File::isFile)?.let(::sha256) != BuildConfig.DECODER_MODEL_SHA256) {
            mutableState.update { it.copy(preparingModel = true) }
            val temporary = File(filesDir, "$name.tmp")
            temporary.delete()
            try {
                getApplication<Application>().assets.open(name).use { asset ->
                    temporary.outputStream().buffered().use { output ->
                        asset.copyTo(output)
                    }
                }
                check(sha256(temporary) == BuildConfig.DECODER_MODEL_SHA256) {
                    "The bundled Vocos model failed its SHA-256 check"
                }
                if (destination.exists()) check(destination.delete()) { "Could not replace Vocos model" }
                check(temporary.renameTo(destination)) { "Could not install Vocos model" }
            } finally {
                temporary.delete()
            }
        }
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun removeObsoleteDecoderModels() {
        val filesDir = getApplication<Application>().filesDir
        File(filesDir, "encodec_48khz_decoder.pte").delete()
        File(filesDir, "encodec_24khz_decoder.pte").delete()
        File(filesDir, "encodec_24khz_decoder_308.pte").delete()
    }
}
