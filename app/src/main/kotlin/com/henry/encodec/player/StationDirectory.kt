package com.henry.encodec.player

import java.io.IOException

/** Decodes the small public station-directory schema without adding a protobuf runtime. */
internal object StationDirectoryParser {
    fun parseProtobuf(bytes: ByteArray): List<RadioStation> {
        val directory = Fields(bytes)
        val version = directory.uint(1)
        require(version in 0..0xffff_ffffL) { "Station directory version is out of range" }
        require(version == 1L) { "Unsupported station directory version: $version" }
        return directory.bytesList(2).mapNotNull { encoded ->
            val station = Fields(encoded)
            val id = station.string(1)
            val name = station.string(2)
            val rawUrl = station.string(3)
            val url = validStationUrl(rawUrl) ?: return@mapNotNull null
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val protobuf = station.optionalBool(4)
            val tcp = station.optionalBool(5)
            val tcpUrl = station.string(6).takeIf(String::isNotBlank)
            RadioStation(
                id = id,
                name = name,
                streamUrl = url,
                protobuf = protobuf,
                tcp = tcp,
                tcpUrl = tcpUrl,
            )
        }.distinctBy(RadioStation::id)
    }

    fun parseJson(text: String): List<RadioStation> {
        val root = org.json.JSONObject(text)
        val entries = root.optJSONArray("stations") ?: root.optJSONArray("streams")
            ?: throw IOException("JSON must contain a 'stations' array")
        return buildList {
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim().ifBlank { item.optString("title").trim() }
                val rawUrl = item.optString("url").trim()
                    .ifBlank { item.optString("streamUrl").trim() }
                    .ifBlank { item.optString("stream_url").trim() }
                val url = validStationUrl(rawUrl) ?: continue
                if (id.isBlank() || name.isBlank()) continue
                add(
                    RadioStation(
                        id = id,
                        name = name,
                        streamUrl = url,
                        protobuf = item.optionalBoolean("protobuf"),
                        tcp = item.optionalBoolean("tcp"),
                        tcpUrl = item.optString("tcp_url").takeIf(String::isNotBlank),
                    ),
                )
            }
        }.distinctBy(RadioStation::id)
    }

    private fun org.json.JSONObject.optionalBoolean(name: String): Boolean? =
        if (!has(name) || isNull(name)) null else opt(name) as? Boolean
            ?: throw IOException("'$name' must be true or false")

    private fun validStationUrl(raw: String): String? {
        val normalized = normalizeStationUrl(raw) ?: return null
        val uri = android.net.Uri.parse(normalized)
        return normalized.takeIf {
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        }
    }

    private class Fields(private val data: ByteArray) {
        private val values = mutableMapOf<Int, MutableList<Pair<Int, Any>>>()

        init {
            var offset = 0
            while (offset < data.size) {
                val (tag, afterTag) = readVarint(offset)
                offset = afterTag
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (field == 0) throw IOException("Invalid station protobuf field tag")
                val value: Any = when (wire) {
                    0 -> readVarint(offset).also { offset = it.second }.first
                    1 -> take(offset, 8).also { offset += 8 }
                    2 -> {
                        val (length, next) = readVarint(offset)
                        offset = next
                        if (length < 0 || length > data.size - offset) {
                            throw IOException("Truncated station protobuf field")
                        }
                        data.copyOfRange(offset, offset + length.toInt()).also { offset += length.toInt() }
                    }
                    5 -> take(offset, 4).also { offset += 4 }
                    else -> throw IOException("Unsupported station protobuf wire type $wire")
                }
                values.getOrPut(field) { mutableListOf() }.add(wire to value)
            }
        }

        fun uint(field: Int): Long = (last(field, 0) as? Long) ?: 0L
        fun string(field: Int): String = String((last(field, 2) as? ByteArray) ?: ByteArray(0), Charsets.UTF_8)
        fun bytesList(field: Int): List<ByteArray> = values[field].orEmpty().map {
            if (it.first != 2) throw IOException("Unexpected wire type for station field $field")
            it.second as ByteArray
        }
        fun optionalBool(field: Int): Boolean? = values[field]?.lastOrNull()?.let {
            if (it.first != 0) throw IOException("Unexpected wire type for station field $field")
            (it.second as Long) != 0L
        }

        private fun last(field: Int, wire: Int): Any? = values[field]?.lastOrNull()?.let {
            if (it.first != wire) throw IOException("Unexpected wire type for station field $field")
            it.second
        }

        private fun take(offset: Int, count: Int): ByteArray {
            if (offset < 0 || count > data.size - offset) throw IOException("Truncated station protobuf field")
            return data.copyOfRange(offset, offset + count)
        }

        private fun readVarint(start: Int): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var offset = start
            while (offset < data.size && shift < 64) {
                val next = data[offset++].toInt() and 0xff
                value = value or ((next and 0x7f).toLong() shl shift)
                if (next and 0x80 == 0) return value to offset
                shift += 7
            }
            throw IOException("Invalid or truncated station protobuf varint")
        }
    }
}

internal data class StationCatalogUrls(val protobuf: String?, val json: String)

internal fun stationCatalogUrls(configuredUrl: String): StationCatalogUrls {
    val withoutSuffix = configuredUrl.substringBefore('?').substringBefore('#')
    return when {
        withoutSuffix.endsWith(".pb", ignoreCase = true) -> StationCatalogUrls(
            protobuf = configuredUrl.replace(Regex("(?i)\\.pb(?=([?#]|$))"), ".pb"),
            json = configuredUrl.replace(Regex("(?i)\\.pb(?=([?#]|$))"), ".json"),
        )
        withoutSuffix.endsWith(".json", ignoreCase = true) -> StationCatalogUrls(
            protobuf = configuredUrl.replace(Regex("(?i)\\.json(?=([?#]|$))"), ".pb"),
            json = configuredUrl,
        )
        else -> StationCatalogUrls(protobuf = null, json = configuredUrl)
    }
}
