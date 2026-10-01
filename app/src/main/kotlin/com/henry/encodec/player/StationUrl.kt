package com.henry.encodec.player

import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal fun normalizeStationSearch(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .trim()

/** Accept plain URLs and URLs accidentally pasted as Markdown links. */
internal fun normalizeStationUrl(value: String): String? {
    val plain = value.trim()
    val candidates = if (plain.startsWith("https://") || plain.startsWith("http://")) {
        listOf(plain)
    } else {
        Regex("https?://[^\\s<>\\[\\]\\(\\)\\\\]+").findAll(plain).map { it.value }.toList()
    }
    return candidates.firstOrNull { candidate ->
        runCatching {
            val uri = URI(candidate)
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
    }
}
