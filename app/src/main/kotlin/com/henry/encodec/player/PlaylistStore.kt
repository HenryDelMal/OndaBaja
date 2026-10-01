package com.henry.encodec.player

import android.content.Context
import android.net.Uri
import com.henry.encodec.ecdc.EcdcHeader
import com.henry.encodec.ecdc.EncodecVariant
import org.json.JSONArray
import org.json.JSONObject

class PlaylistStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PlayerState = runCatching {
        val raw = preferences.getString(PLAYLIST_KEY, null) ?: return PlayerState()
        val root = JSONObject(raw)
        val itemsJson = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(index)
                val variant = EncodecVariant.fromWireName(item.getString("variant"))
                val header = EcdcHeader(
                    version = item.getInt("version"),
                    variant = variant,
                    audioLengthSamples = item.getLong("audioLengthSamples"),
                    numCodebooks = item.getInt("numCodebooks"),
                    usesLanguageModel = item.getBoolean("usesLanguageModel"),
                )
                if (!header.usesLanguageModel) {
                    add(
                        PlaylistItem(
                            uri = Uri.parse(item.getString("uri")),
                            title = item.getString("title"),
                            header = header,
                        ),
                    )
                }
            }
        }
        val storedIndex = root.optInt("currentIndex", 0)
        val currentIndex = if (items.isEmpty()) -1 else storedIndex.coerceIn(items.indices)
        val libraryJson = root.optJSONArray("libraryTracks") ?: itemsJson
        val libraryTracks = buildList {
            for (index in 0 until libraryJson.length()) {
                val item = runCatching { libraryJson.getJSONObject(index) }.getOrNull() ?: continue
                runCatching {
                    val variant = EncodecVariant.fromWireName(item.getString("variant"))
                    val header = EcdcHeader(
                        version = item.getInt("version"),
                        variant = variant,
                        audioLengthSamples = item.getLong("audioLengthSamples"),
                        numCodebooks = item.getInt("numCodebooks"),
                        usesLanguageModel = item.getBoolean("usesLanguageModel"),
                    )
                    if (!header.usesLanguageModel) PlaylistItem(
                        Uri.parse(item.getString("uri")), item.getString("title"), header,
                    ) else null
                }.getOrNull()?.let(::add)
            }
        }.distinctBy { it.uri }
        val repeatMode = runCatching {
            RepeatMode.valueOf(root.optString("repeatMode", RepeatMode.OFF.name))
        }.getOrDefault(RepeatMode.OFF)
        val livestreamsJson = root.optJSONArray("livestreams") ?: JSONArray()
        val livestreams = buildList<SavedLiveStream> {
            for (index in 0 until livestreamsJson.length()) {
                val item = livestreamsJson.getJSONObject(index)
                val manifestUrl = item.optString("manifestUrl").trim()
                if (manifestUrl.isNotEmpty() && none { it.manifestUrl == manifestUrl }) {
                    add(
                        SavedLiveStream(
                            manifestUrl = manifestUrl,
                            title = item.optString("title", manifestUrl),
                            variant = item.optString("variant").takeIf { it.isNotBlank() }
                                ?.let(EncodecVariant::fromWireName),
                            codebooks = item.optInt("codebooks", 0).takeIf { it > 0 },
                            bandwidthKbps = item.optDouble("bandwidthKbps", Double.NaN)
                                .takeIf { it.isFinite() },
                        ),
                    )
                }
            }
        }
        val recent = root.optJSONArray("recentItems")?.let { values ->
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.optString(index)
                    if (value.startsWith("track:") || value.startsWith("stream:")) add(value)
                }
            }.distinct().take(20)
        }.orEmpty()
        PlayerState(
            playlist = items,
            libraryTracks = libraryTracks,
            livestreams = livestreams,
            currentIndex = currentIndex,
            shuffle = root.optBoolean("shuffle", false),
            repeatMode = repeatMode,
            diagnosticsEnabled = root.optBoolean("diagnosticsEnabled", false),
            experimentalVocos = root.optBoolean("experimentalVocos", false),
            rescaleEnabled = root.optBoolean("rescaleEnabled", false),
            recentItems = recent,
        )
    }.getOrElse { PlayerState() }

    fun save(state: PlayerState) {
        fun serializeTracks(tracks: List<PlaylistItem>): JSONArray = JSONArray().apply {
            tracks.forEach { playlistItem ->
                put(
                    JSONObject()
                        .put("uri", playlistItem.uri.toString())
                        .put("title", playlistItem.title)
                        .put("version", playlistItem.header.version)
                        .put("variant", playlistItem.header.variant.wireName)
                        .put("audioLengthSamples", playlistItem.header.audioLengthSamples)
                        .put("numCodebooks", playlistItem.header.numCodebooks)
                        .put("usesLanguageModel", playlistItem.header.usesLanguageModel),
                )
            }
        }
        val items = serializeTracks(state.playlist)
        val libraryTracks = serializeTracks(state.libraryTracks)
        val livestreams = JSONArray()
        state.livestreams.forEach { stream ->
            livestreams.put(
                JSONObject()
                    .put("manifestUrl", stream.manifestUrl)
                    .put("title", stream.title)
                    .apply {
                        stream.variant?.let { put("variant", it.wireName) }
                        stream.codebooks?.let { put("codebooks", it) }
                        stream.bandwidthKbps?.let { put("bandwidthKbps", it) }
                    },
            )
        }
        val root = JSONObject()
            .put("items", items)
            .put("queueVersion", 2)
            .put("libraryTracks", libraryTracks)
            .put("livestreams", livestreams)
            .put("currentIndex", state.currentIndex)
            .put("shuffle", state.shuffle)
            .put("repeatMode", state.repeatMode.name)
            .put("diagnosticsEnabled", state.diagnosticsEnabled)
            .put("experimentalVocos", state.experimentalVocos)
            .put("rescaleEnabled", state.rescaleEnabled)
            .put("recentItems", JSONArray().apply { state.recentItems.forEach { put(it) } })
        preferences.edit().putString(PLAYLIST_KEY, root.toString()).apply()
    }

    fun loadLastLiveUrl(): String =
        (preferences.getString(LAST_LIVE_URL_KEY, "") ?: "")
            .takeUnless { it == "https://example.com/stream/stream.json" }
            .orEmpty()

    fun saveLastLiveUrl(url: String) {
        preferences.edit().putString(LAST_LIVE_URL_KEY, url).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "encodec_player"
        const val PLAYLIST_KEY = "saved_playlist_v1"
        const val LAST_LIVE_URL_KEY = "last_live_url"
    }
}
