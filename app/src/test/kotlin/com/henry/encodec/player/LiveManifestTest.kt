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

class LiveManifestTest {
    @Test
    fun parsesManifestAndResolvesRelativeUris() {
        val parsed = LiveManifestParser.parse(
            manifestJson(listOf(segment(10)), mediaSequence = 10),
            MANIFEST_URL,
        )
        assertNull(parsed.title)
        assertEquals("https://example.com/live/segment-10.ecdc", parsed.segments.single().url)
        assertEquals(96_000L, requireNotNull(parsed.segments.single().sampleCount))
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

        assertEquals(48_000L, requireNotNull(parsed.segments.single().sampleCount))
    }

    @Test
    fun startsWithSixSegmentsAvailableThroughPublishedEdge() {
        val tracker = LiveSequenceTracker(12_000)
        val manifest = manifest((10L..15L).map(::info))
        assertEquals(10L, tracker.select(manifest)?.sequence)
    }

    @Test
    fun startupLookbackAdaptsToManifestSegmentDurations() {
        val twoSecondSegments = (10L..20L).map { info(it).copy(duration = 2.0) }
        val fiveSecondSegments = (10L..20L).map { info(it).copy(duration = 5.0) }
        val mixedDurationSegments = listOf(
            info(10).copy(duration = 4.0),
            info(11).copy(duration = 5.0),
            info(12).copy(duration = 2.0),
            info(13).copy(duration = 3.0),
        )

        assertEquals(5, LiveSequenceTracker.startupIndex(twoSecondSegments, 12_000))
        assertEquals(8, LiveSequenceTracker.startupIndex(fiveSecondSegments, 12_000))
        assertEquals(0, LiveSequenceTracker.startupIndex(mixedDurationSegments, 12_000))
    }

    @Test
    fun thirtySecondStartupKeepsDeletionMarginInEightAndSixteenSegmentWindows() {
        val fiveSeconds = manifest((10L..17L).map { info(it).copy(duration = 5.0) })
        assertEquals(12L, LiveSequenceTracker(30_000).select(fiveSeconds)?.sequence)
        val twoSeconds = manifest((10L..25L).map { info(it).copy(duration = 2.0) })
        assertEquals(12L, LiveSequenceTracker(30_000).select(twoSeconds)?.sequence)
    }

    @Test
    fun expiredOlderSegmentRefreshesManifestAndDownloadedSegmentIsReused() = runBlocking {
        val bytes = ecdcHeader(240_000, 8)
        var fetches = 0
        val requests = mutableListOf<Long>()
        val source = LiveStreamSource(
            MANIFEST_URL.replace("stream.json", "index.json"),
            startupLookbackMs = 30_000,
            fetchManifestBytes = {
                fetches++
                val range = when (fetches) {
                    1 -> 10L..17L
                    2 -> 11L..18L
                    else -> 16L..23L
                }
                manifestJson(range.map { segment(it, bytes.size, bytes.sha256(), 5.0) }, range.first)
                    .replace("\"target_duration\":2.0", "\"target_duration\":5.0").toByteArray()
            },
            fetchSegmentBytes = { url ->
                val sequence = url.substringAfter("segment-").substringBefore(".ecdc").toLong()
                requests += sequence
                if (sequence == 12L || sequence == 14L) throw java.io.IOException("Server returned HTTP 404")
                bytes
            },
        )
        source.initialize {}
        assertEquals(13L, source.nextSegment {}.sequence)
        assertEquals(1, requests.count { it == 13L })
        assertEquals(18L, source.nextSegment {}.sequence)
        assertEquals(listOf(12L, 13L, 14L, 18L), requests)
        assertEquals(3, fetches)
    }

    @Test
    fun unchangedManifestDoesNotDuplicateSequence() {
        val tracker = LiveSequenceTracker(12_000)
        val manifest = manifest(listOf(info(20)))
        val selected = requireNotNull(tracker.select(manifest))
        tracker.accept(selected)
        assertNull(tracker.select(manifest))
    }

    @Test
    fun networkRecoveryNeverRepeatsAlreadyAcceptedFiveSecondSegments() {
        val tracker = LiveSequenceTracker(12_000)
        val first = manifest((10L..17L).map { info(it).copy(duration = 5.0) })
        for (sequence in 15L..17L) {
            assertEquals(sequence, tracker.accept(requireNotNull(tracker.select(first))).segment.sequence)
        }
        tracker.jumpToSafeLivePosition()
        assertNull(tracker.select(first))
        val updated = manifest((11L..18L).map { info(it).copy(duration = 5.0) })
        assertEquals(18L, tracker.accept(requireNotNull(tracker.select(updated))).segment.sequence)
        assertNull(tracker.select(updated))
    }

    @Test
    fun networkRecoveryChoosesNextUnconsumedSegmentInsideOverlappingWindow() {
        val tracker = LiveSequenceTracker(12_000)
        val current = manifest((10L..17L).map { info(it).copy(duration = 5.0) })
        tracker.accept(requireNotNull(tracker.select(current)))
        tracker.jumpToSafeLivePosition()
        assertEquals(16L, tracker.select(current)?.sequence)
    }

    @Test
    fun progressesThroughEveryPublishedSegmentBeforeRefreshing() {
        val tracker = LiveSequenceTracker(12_000)
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
        val tracker = LiveSequenceTracker(12_000)
        tracker.accept(info(40))
        val overtaken = manifest((50L..55L).map(::info))
        val selected = requireNotNull(tracker.select(overtaken))
        assertEquals(50, selected.sequence)
        assertTrue(tracker.accept(selected).discontinuity)
    }

    @Test
    fun sequenceGapEpochChangeAndMarkerAreDiscontinuities() {
        val tracker = LiveSequenceTracker(12_000)
        tracker.accept(info(60))
        assertTrue(tracker.accept(info(62)).discontinuity)
        assertTrue(tracker.accept(info(63, epoch = EPOCH_2)).discontinuity)
        assertTrue(tracker.accept(info(64, epoch = EPOCH_2, discontinuity = true)).discontinuity)
    }

    @Test
    fun ignoresRedundantCodecFieldsAndRejectsUnorderedSequences() {
        val withoutInit = manifestJson(listOf(segment(1))).replace(Regex("\\s*\"init\":\\{[^}]+},?"), "")
        assertEquals(1, LiveManifestParser.parse(withoutInit, MANIFEST_URL).segments.size)
        val inconsistentInit = manifestJson(listOf(segment(1)))
            .replace("\"sample_rate\":48000", "\"sample_rate\":24000")
        assertEquals(1, LiveManifestParser.parse(inconsistentInit, MANIFEST_URL).segments.size)
        assertThrows(LiveProtocolException::class.java) {
            LiveManifestParser.parse(manifestJson(listOf(segment(2), segment(1))), MANIFEST_URL)
        }
    }

    @Test
    fun rejectsMalformedSegmentFields() {
        val valid = manifestJson(listOf(segment(1)))
        val invalidDocuments = listOf(
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
        val tracker = LiveSequenceTracker(12_000)
        val manifest = manifest((80L..85L).map(::info))
        tracker.accept(requireNotNull(tracker.select(manifest)))
        tracker.reset()
        assertEquals(80L, tracker.select(manifest)?.sequence)

        val source = LiveStreamSource(
            MANIFEST_URL,
            startupLookbackMs = 12_000,
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
    fun sourceDownloadsSixCachedSegmentsInOrderThenRefreshesForTwoAndFiveSecondStreams() = runBlocking {
        for (duration in listOf(2.0, 5.0)) {
            val bytes = ecdcHeader((duration * 48_000).toLong(), 8)
            val hash = bytes.sha256()
            var manifestFetches = 0
            val fetchedSequences = mutableListOf<Long>()
            val source = LiveStreamSource(
                // This fixture returns JSON only; avoid a sibling protobuf
                // probe advancing its simulated manifest before JSON is read.
                MANIFEST_URL.replace("stream.json", "index.json"),
                startupLookbackMs = 12_000,
            fetchManifestBytes = {
                    manifestFetches++
                    val edge = if (manifestFetches == 1) 25L else 26L
                    manifestJson(
                        (10L..edge).map { segment(it, bytes.size, hash, duration) },
                        mediaSequence = 10,
                    ).replace("\"target_duration\":2.0", "\"target_duration\":$duration").toByteArray()
                },
                fetchSegmentBytes = { url ->
                    fetchedSequences += url.substringAfter("segment-").substringBefore(".ecdc").toLong()
                    bytes
                },
            )

            val codec = source.initialize {}
            assertEquals(EncodecVariant.STEREO_48_KHZ, codec.variant)
            assertEquals(8, codec.codebooks)
            assertEquals(12.0, codec.bandwidthKbps, 0.0)
            val firstSequence = if (duration == 2.0) 20L else 23L
            for (sequence in firstSequence..25L) {
                val downloaded = source.nextSegment {}
                assertEquals(sequence, downloaded.sequence)
                assertEquals(duration, downloaded.durationSeconds, 0.0001)
                assertEquals(sequence == 25L, downloaded.reachedManifestEdge)
                assertEquals(1, manifestFetches)
            }
            assertEquals((firstSequence..25L).toList(), fetchedSequences)
            assertEquals(26L, source.nextSegment {}.sequence)
            assertEquals(2, manifestFetches)
        }
    }

    @Test
    fun refreshesManifestAfterNetworkFailureSoExpiredSegmentsCanBeSkipped() = runBlocking {
        val bytes = ecdcHeader(96_000, 8)
        val hash = bytes.sha256()
        var manifestFetches = 0
        var failSecondSegmentOnce = true
        val source = LiveStreamSource(
            MANIFEST_URL.replace("stream.json", "index.json"),
            startupLookbackMs = 12_000,
            fetchManifestBytes = {
                manifestFetches++
                val range = if (manifestFetches == 1) 1L..3L else 7L..9L
                manifestJson(
                    range.map { segment(it, bytes.size, hash) },
                    mediaSequence = range.first,
                ).toByteArray()
            },
            fetchSegmentBytes = { url ->
                if (url.endsWith("segment-2.ecdc") && failSecondSegmentOnce) {
                    failSecondSegmentOnce = false
                    throw java.io.IOException("simulated network outage")
                }
                bytes
            },
        )

        source.initialize {}
        assertEquals(1L, source.nextSegment {}.sequence)
        val recovered = source.nextSegment {}

        assertEquals(7L, recovered.sequence)
        assertTrue(recovered.discontinuity)
        assertEquals(2, manifestFetches)
    }

    private fun manifest(segments: List<LiveSegmentInfo>) = LiveManifest(
        mediaSequence = segments.firstOrNull()?.sequence ?: 0,
        targetDuration = 2.0,
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
        sampleCount, epoch, discontinuity, byteLength, sha256,
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
        duration: Double = 2.0,
    ): String = """
        {"sequence":$sequence,"uri":"segment-$sequence.ecdc","duration":$duration,
         "sample_count":${(duration * 48_000).toLong()},"pts_samples":${sequence * (duration * 48_000).toLong()},
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
