package com.henry.encodec.player

import org.junit.Assert.*
import org.junit.Test

class StationUrlTest {
    @Test fun acceptsPlainAndMarkdownCatalogUrls() {
        val url = "https://cuy.cl/cooperativa/stream.json"
        assertEquals(url, normalizeStationUrl(url))
        assertEquals(url, normalizeStationUrl("[$url]($url)"))
        assertEquals(url, normalizeStationUrl("[[$url\\]($url)]($url]\\($url\\))"))
        assertNull(normalizeStationUrl("not a station URL"))
    }
}
