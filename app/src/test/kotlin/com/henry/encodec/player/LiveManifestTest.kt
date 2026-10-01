package com.henry.encodec.player

import com.henry.encodec.ecdc.EncodecVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant

class LiveManifestTest {
    @Test
    fun parsesManifestAndResolvesRelativeUris() {
        val parsed = LiveManifestParser.parse(
            manifestJson(listOf(segment(10)), mediaSequence = 10),
            MANIFEST_URL,
        )
        assertEquals(8, parsed.init.codebooks)
        assertNull(parsed.title)
        assertEquals("https://example.com/live/segment-10.ecdc", parsed.segments.single().url)
        assertEquals(96_000, parsed.segments.single().sampleCount)
    }

    @Test
    fun parsesOptionalStreamTitle() {
        val document = manifestJson(listOf(segment(10)), mediaSequence = 10)
            .replace(
                "\"format\":\"encodec-live-v1\"",
                "\"format\":\"encodec-live-v1\",\"title\":\"Radio Bio Bio Santiago\"",
            )

        val parsed = LiveManifestParser.parse(document, MANIFEST_URL)

        assertEquals("Radio Bio Bio Santiago", parsed.title)
    }

    @Test
    fun parses24KhzMonoManifest() {
        val parsed = LiveManifestParser.parse(
            manifestJson(listOf(segment(10)), mediaSequence = 10)
                .replace("\"model\":\"encodec_48khz\"", "\"model\":\"encodec_24khz\"")
                .replace("\"sample_rate\":48000", "\"sample_rate\":24000")
                .replace("\"channels\":2", "\"channels\":1")
                .replace("\"bandwidth_kbps\":12.0", "\"bandwidth_kbps\":6.0")
                .replace("\"sample_count\":96000", "\"sample_count\":48000")
                .replace("\"pts_samples\":960000", "\"pts_samples\":480000"),
            MANIFEST_URL,
        )

        assertEquals(EncodecVariant.MONO_24_KHZ, parsed.init.variant)
        assertEquals(6.0, parsed.init.bandwidthKbps, 0.0)
        assertEquals(48_000, parsed.segments.single().sampleCount)
    }

    @Test
    fun startsWithSixSegmentsAvailableThroughPublishedEdge() {
        val tracker = LiveSequenceTracker()
        val manifest = manifest((10L..15L).map(::info))
        assertEquals(10L, tracker.select(manifest)?.sequence)
    }

    @Test
    fun startsPlaybackWithOneSegmentThenFillsTheBackgroundTarget() {
        assertEquals(1, requiredLiveBufferDepth(deliveredSegments = 0, rebufferTarget = 2))
        assertEquals(2, requiredLiveBufferDepth(deliveredSegments = 1, rebufferTarget = 2))
    }

    @Test
    fun unchangedManifestDoesNotDuplicateSequence() {
        val tracker = LiveSequenceTracker()
        val manifest = manifest(listOf(info(20)))
        val selected = requireNotNull(tracker.select(manifest))
        tracker.accept(selected)
        assertNull(tracker.select(manifest))
    }

    @Test
    fun progressesThroughEveryPublishedSegmentBeforeRefreshing() {
        val tracker = LiveSequenceTracker()
        val firstManifest = manifest((30L..34L).map(::info))
        assertEquals(30, tracker.accept(requireNotNull(tracker.select(firstManifest))).segment.sequence)
        assertEquals(31, tracker.accept(requireNotNull(tracker.select(firstManifest))).segment.sequence)
        assertEquals(32, tracker.accept(requireNotNull(tracker.select(firstManifest))).segment.sequence)
        assertEquals(33, tracker.accept(requireNotNull(tracker.select(firstManifest))).segment.sequence)
        assertEquals(34, tracker.accept(requireNotNull(tracker.select(firstManifest))).segment.sequence)
        assertNull(tracker.select(firstManifest))

        val updatedManifest = manifest((30L..35L).map(::info))
        assertEquals(35, tracker.accept(requireNotNull(tracker.select(updatedManifest))).segment.sequence)
        assertNull(tracker.select(updatedManifest))
    }

    @Test
    fun cleanupOvertakeJumpsNearLiveEdgeAndMarksDiscontinuity() {
        val tracker = LiveSequenceTracker()
        tracker.accept(info(40))
        val overtaken = manifest((50L..55L).map(::info))
        val selected = requireNotNull(tracker.select(overtaken))
        assertEquals(50, selected.sequence)
        assertTrue(tracker.accept(selected).discontinuity)
    }

    @Test
    fun sequenceGapEpochChangeAndMarkerAreDiscontinuities() {
        val tracker = LiveSequenceTracker()
        tracker.accept(info(60))
        assertTrue(tracker.accept(info(62)).discontinuity)
        assertTrue(tracker.accept(info(63, epoch = EPOCH_2)).discontinuity)
        assertTrue(tracker.accept(info(64, epoch = EPOCH_2, discontinuity = true)).discontinuity)
    }

    @Test
    fun rejectsIncompatibleInitializationAndUnorderedSequences() {
        assertThrows(LiveProtocolException::class.java) {
            LiveManifestParser.parse(
                manifestJson(listOf(segment(1))).replace("\"sample_rate\":48000", "\"sample_rate\":24000"),
                MANIFEST_URL,
            )
        }
        assertThrows(LiveProtocolException::class.java) {
            LiveManifestParser.parse(manifestJson(listOf(segment(2), segment(1))), MANIFEST_URL)
        }
    }

    @Test
    fun rejectsUnsupportedCodecFlagsAndMalformedSegmentFields() {
        val valid = manifestJson(listOf(segment(1)))
        val invalidDocuments = listOf(
            valid.replace("\"container_version\":0", "\"container_version\":1"),
            valid.replace("\"model\":\"encodec_48khz\"", "\"model\":\"encodec_24khz\""),
            valid.replace("\"channels\":2", "\"channels\":1"),
            valid.replace("\"bits_per_codebook\":10", "\"bits_per_codebook\":9"),
            valid.replace("\"language_model\":false", "\"language_model\":true"),
            valid.replace("\"codebooks\":8", "\"codebooks\":3"),
            valid.replace("\"duration\":2.0", "\"duration\":0.0"),
            valid.replace("\"sha256\":\"${"a".repeat(64)}\"", "\"sha256\":\"bad\""),
            valid.replace(
                "\"format\":\"encodec-live-v1\"",
                "\"format\":\"encodec-live-v1\",\"title\":\"${"x".repeat(201)}\"",
            ),
        )
        invalidDocuments.forEach { document ->
            assertThrows(LiveProtocolException::class.java) {
                LiveManifestParser.parse(document, MANIFEST_URL)
            }
        }
    }

    @Test
    fun rejectsByteLengthAndShaFailures() {
        val bytes = ecdcHeader(96_000, 8)
        val valid = info(
            sequence = 70,
            byteLength = bytes.size,
            sha256 = bytes.sha256(),
            sampleCount = 96_000,
        )
        val init = LiveCodecInit(EncodecVariant.STEREO_48_KHZ, 12.0, 8)
        LiveStreamSource.verifySegment(bytes, valid, init)
        assertThrows(LiveProtocolException::class.java) {
            LiveStreamSource.verifySegment(bytes, valid.copy(byteLength = bytes.size + 1), init)
        }
        assertThrows(LiveProtocolException::class.java) {
            LiveStreamSource.verifySegment(bytes, valid.copy(sha256 = "0".repeat(64)), init)
        }
    }

    @Test
    fun verifies24KhzMonoSegmentInitialization() {
        val bytes = ecdcHeader(48_000, 8, "encodec_24khz")
        val segment = info(
            sequence = 71,
            byteLength = bytes.size,
            sha256 = bytes.sha256(),
            sampleCount = 48_000,
        )
        val init = LiveCodecInit(EncodecVariant.MONO_24_KHZ, 6.0, 8)

        LiveStreamSource.verifySegment(bytes, segment, init)
    }

    @Test
    fun resetReconnectsAtLiveEdgeAndSourceIsCancellable() = runBlocking {
        val tracker = LiveSequenceTracker()
        val manifest = manifest((80L..85L).map(::info))
        tracker.accept(requireNotNull(tracker.select(manifest)))
        tracker.reset()
        assertEquals(80L, tracker.select(manifest)?.sequence)

        val source = LiveStreamSource(
            MANIFEST_URL,
            fetchManifestBytes = { delay(Long.MAX_VALUE); byteArrayOf() },
            fetchSegmentBytes = { byteArrayOf() },
        )
        var cancelled = false
        val job = launch {
            try {
                source.nextSegment {}
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        job.cancel()
        job.join()
        assertTrue(cancelled || job.isCancelled)
    }

    @Test
    fun sourceUsesWholeManifestBatchBeforeRefreshing() = runBlocking {
        val bytes = ecdcHeader(96_000, 8)
        val hash = bytes.sha256()
        var manifestFetches = 0
        val source = LiveStreamSource(
            MANIFEST_URL,
            fetchManifestBytes = {
                manifestFetches++
                manifestJson(
                    (1L..(manifestFetches + 2L)).map { segment(it, bytes.size, hash) },
                ).toByteArray()
            },
            fetchSegmentBytes = { bytes },
        )

        assertEquals(EncodecVariant.STEREO_48_KHZ, source.initialize {}.variant)
        assertEquals(1L, source.nextSegment {}.sequence)
        assertEquals(2L, source.nextSegment {}.sequence)
        assertEquals(1, manifestFetches)
    }

    private fun manifest(segments: List<LiveSegmentInfo>) = LiveManifest(
        mediaSequence = segments.firstOrNull()?.sequence ?: 0,
        discontinuitySequence = 0,
        targetDuration = 2.0,
        init = LiveCodecInit(EncodecVariant.STEREO_48_KHZ, 12.0, 8),
        segments = segments,
    )

    private fun info(
        sequence: Long,
        epoch: String = EPOCH_1,
        discontinuity: Boolean = false,
        byteLength: Int = 100,
        sha256: String = "a".repeat(64),
        sampleCount: Long = 96_000,
    ) = LiveSegmentInfo(
        sequence, "https://example.com/live/segment-$sequence.ecdc", 2.0,
        sampleCount, sequence * 96_000, Instant.parse("2026-08-23T00:00:00Z"),
        epoch, discontinuity, byteLength, sha256,
    )

    private fun manifestJson(segments: List<String>, mediaSequence: Long = 1): String = """
        {
          "format":"encodec-live-v1","version":1,"updated_at":"2026-08-23T00:00:00Z",
          "media_sequence":${if (segments.isEmpty()) 0 else mediaSequence},
          "discontinuity_sequence":0,"target_duration":2.0,"independent_segments":true,
          "init":{"container":"ecdc","container_version":0,"model":"encodec_48khz",
            "sample_rate":48000,"channels":2,"bits_per_codebook":10,"bandwidth_kbps":12.0,
            "codebooks":8,"language_model":false,"self_initializing_segments":true},
          "segments":[${segments.joinToString(",")}]
        }
    """.trimIndent()

    private fun segment(
        sequence: Long,
        byteLength: Int = 100,
        sha256: String = "a".repeat(64),
    ): String = """
        {"sequence":$sequence,"uri":"segment-$sequence.ecdc","duration":2.0,
         "sample_count":96000,"pts_samples":${sequence * 96_000},
         "program_date_time":"2026-08-23T00:00:00Z","epoch":"$EPOCH_1",
         "discontinuity":false,"byte_length":$byteLength,"sha256":"$sha256"}
    """.trimIndent()

    private fun ecdcHeader(
        samples: Long,
        codebooks: Int,
        model: String = "encodec_48khz",
    ): ByteArray {
        val metadata = "{\"m\":\"$model\",\"al\":$samples,\"nc\":$codebooks,\"lm\":false}"
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeBytes("ECDC")
                out.writeByte(0)
                out.writeInt(metadata.toByteArray().size)
                out.writeBytes(metadata)
            }
        }.toByteArray()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST_URL = "https://example.com/live/stream.json"
        const val EPOCH_1 = "11111111-1111-1111-1111-111111111111"
        const val EPOCH_2 = "22222222-2222-2222-2222-222222222222"
    }
}
