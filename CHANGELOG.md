# Changelog

## OndaBaja

- Prepare the first OndaBaja release (version 1.0.0, version code 68).
- Add the OndaBaja GitHub repository link to About > Technologies and attributions.
- Add a root-level ignored build config, a tracked config example, and a fictional station catalog JSON example.
- Normalize Spanish accent marks in station search and keep previous/next within the selected Todas or Favoritas list.
- Replace station card play/pause glyphs with animated live signal icons. Expose Stop in media controls and stop livestream playback when Android sends Pause.
- Advance the Android package version to 0.1.8 (version code 67).
- Clarify in bold that the app needs active internet and may be slow on a weak connection.
- Advance the Android package version to 0.1.7 (version code 66).
- Add persistent favorite stations and a Favoritas tab beside Todas.
- Replace the live player pause control with Stop; Play reconnects to the selected station.
- Restructure About and Advanced screens with per-project links and full license views.
- Add the app's MIT license and third-party notices, including AndroidX and Kotlin.
- Advance the Android package version to 0.1.6 (version code 65).
- Created a separate OndaBaja app with a compact station browser,
  a fixed station catalog URL, and a warm dark-red visual theme.
- Removed the track queue and file library UI; selected stations play directly.
- Restricted EnCodec Live playback to compatible 24 kHz mono streams with Vocos.
- Package the original uncompressed Vocos model and remove the Zstandard extraction dependency.
- Explicitly remove the playback foreground notification when its service stops and clear stale playback services at app startup.

## 0.11.12

- Keep previously played tracks visible in the Queue when advancing with Next;
  playback advances the current position without removing queue entries.
- Removing a non-current queue entry no longer stops or restarts playback. Removing
  the current entry advances to the next available track when playback is active.
- Replace the springy drag reordering animation with a restrained, non-overshooting
  slide and a lighter lift effect.

## 0.11.11

- Selecting a track from the library replaces the current queue item instead of
  silently adding another queued track. Use Add to Queue to add upcoming tracks.
- Keep Home's Queue view at the top when the current track changes so the active
  item remains visible.

## 0.11.10

- Fixed queue progression so Next follows the displayed order without returning
  to the previously playing track.
- Keep a stable playlist order and current position; Home shows the current
  track first followed by upcoming tracks, and drag reorders upcoming entries.

## 0.11.9

- Reworked queue and livestream reordering to lift the active row, have it
  follow the finger continuously, and animate neighboring rows into place.

## 0.11.8

- Split the saved Tracks library from the playback queue while migrating
  existing saved tracks automatically.
- Added Home Queue and Recents tabs, a clear-queue action, and per-track menu
  actions to add to queue or delete from the library.
- Keep the playing track at the top of the queue; track selection promotes it
  there, while manual ordering applies to upcoming tracks.
- Improved drag feedback with lift, scale, and transparency while reordering.
- Moved playback diagnostics under Advanced settings.
- Replaced the Streams emoji with a matching outlined globe icon.
- Changed the full-screen overflow button into a real menu; stopping playback
  now requires choosing the clearly labeled Stop playback action.

## 0.11.7

- Return to Home after choosing a track or saved stream from its browse list.
- Replace Home's saved-station cards with the current track queue and recent tracks
  and stations; add tracks to the queue directly from Home.
- Reorder queued tracks and saved livestream stations with a long press and drag.
- Previous/next now switch between saved livestream stations while listening live.

## 0.11.6

- Add a saved Playback setting for decoder peak rescaling, disabled by default.
- Apply the setting to both EnCodec and experimental Vocos decoding, including
  live streams; changing it during playback restarts from the current position.

## 0.11.5

- Android Back now returns through player screens and settings before leaving the app.
- Replace the bottom Add URL shortcut with a Streams library destination.
- Add separate JSON import/export for saved tracks and livestreams.
- Update playback progress on a one-second timer independent of decoder chunk timing.
- Randomize live waveform bar heights and animate a single mirrored bar pair outward
  from the center while a livestream is playing.
- Use the waveform mark as the launcher app icon.

## 0.11.4

- Animate the track waveform with changing bar heights and playback-progress fill.
- Add an infinitely repeating center-out antenna pulse for active livestreams;
  it pauses when playback is paused.

## 0.11.3

- Redesigned the player with a station-focused home screen, separate Tracks and
  Live browsing, a full-screen now-playing view, and category-based settings.
- Added a soft violet light theme and selectable dark/system appearance.
- Kept playback, seek, shuffle, repeat, saved stations, URL opening, and decoder
  options available in the new layout.

## 0.11.2

- Make the Vocos setting apply its requested on/off state directly and clarify
  when the current stream format supports Vocos.
- Show which decoder is active in livestream status and use a globe icon for
  opening remote URLs.

## 0.11.1

- Compact player controls and playlist layout; moved decoder and diagnostics
  options into Settings.
- Apply system-bar insets so the app content stays clear of the Android status
  and navigation bars.

## 0.11.0

- Added a persistent experimental Vocos decoder button for existing 24 kHz mono
  EnCodec files and livestreams at 1.5, 3, 6 and 12 kbps. No re-encoding is needed.
- Bundled the official `charactr/vocos-encodec-24khz` checkpoint as native float32
  weights. Inference uses C++/Eigen with one worker, bandwidth-conditioned
  normalization and inverse-STFT synthesis; no Python or ML runtime is needed.
- Retained peak rescaling. 48 kHz stereo and 24 kbps mono use the EnCodec decoder.
- Added bounded future-token context to Vocos file decoding to avoid artifacts
  at internal chunk boundaries, preserving exact output length and seeking.
- Changing the decoder restarts finite playback at its current position or
  reconnects the active livestream. EnCodec remains the default.
- Added checkpoint export instructions and numerical parity checks against the
  official Vocos implementation. Phone performance and battery use remain experimental.

## 0.10.7

- Added Android Performance Hint integration for the existing single native
  decoder worker. Android 15 devices are asked to prefer power-efficient CPU
  scheduling with a safe deadline derived from each audio frame's duration.
- Removed the high-performance Wi-Fi lock that kept Wi-Fi in a high-power mode
  during locked-screen playback. The partial CPU wake lock is now released
  while playback is paused.
- Made verbose native, network, queue and AudioTrack diagnostics optional and
  disabled by default, with an in-app toggle for troubleshooting.
- Reduced locked-screen/UI work by lowering finite-file position updates to
  once per second and polling unchanged live manifests less aggressively.
- Added gzip support for `stream.json` while keeping ECDC segment compression
  disabled.
- Consume every complete segment listed by one manifest response and batch
  subsequent manifest refreshes around buffered playback. This reduces tiny
  radio wakeups on both Wi-Fi and mobile data without increasing live latency.
- Increased the one-response startup window to six segments while retaining
  immediate playback after the first download; remaining segments are fetched
  by the background producer.
- Cached saved-stream codec metadata so the expected decoder can load in
  parallel with a slow manifest request. The downloaded manifest remains
  authoritative and stale cached parameters are never used for playback.
- Automatically reconnect and rebuild the decoder and AudioTrack when a live
  manifest changes its model, sample rate, channels, codebooks or bitrate.
- Removed unconditional 48 kHz model preloading, which could delay a 24 kHz
  stream by loading and immediately replacing the wrong model.

## 0.10.6

- Detect stalled live requests after five seconds and retry them on a fresh
  connection instead of letting a dead response block the downloader for up
  to 30 seconds.
- Increased the initial background cushion to three queued successors while
  retaining immediate playback as soon as the first segment is available.
- Grow the adaptive buffer target proactively when a segment takes longer to
  download than its playback duration.
- Added response-header and body timings plus decoder initialization timing to
  the `EnCodecLive` diagnostic log.
- Corrected the startup status so stream-format checking and decoder loading
  are reported as separate operations.

## 0.10.5

- Added an `EnCodecLive` diagnostic timeline for manifest fetches, segment
  downloads, compressed queue changes, rebuffer events, decoding and
  `AudioTrack` back-pressure.
- Included sequence numbers, elapsed times, byte counts, queue depths and
  thread names so slow live playback can be diagnosed from one filtered log.

## 0.10.4

- Isolated live manifest and segment HTTP work on a dedicated sequential
  downloader thread so blocking native decoding cannot delay network prefetch.
- Kept ordered downloads and the existing HTTP keep-alive behavior while
  separating the producer from the decoder executor.

## 0.10.3

- Kept one-segment immediate livestream startup while allowing the background
  downloader to queue two safe successors before segment 2 is consumed. With
  the current segment, this provides three downloaded segments in total.
- Renamed the live counter from `downloaded` to `queued`; it intentionally does
  not include the segment already handed to the decoder or `AudioTrack`.

## 0.10.2

- Restored immediate livestream startup after the first verified segment instead
  of waiting for the three-segment adaptive rebuffer target.
- Live segments continue downloading into the bounded queue in the background
  while the first approximately 4–5 second segment is decoded and played.
- Initial startup no longer counts as a rebuffer event or increases the adaptive
  buffer target.
- Moved native model loading, model changes and `AudioTrack` creation off the UI
  thread so playback buttons remain responsive during setup.
- Prevented bounded blocking `AudioTrack` writes from holding the monitor needed
  by pause, stop and seek controls.

## 0.10.1

- Added support for the optional top-level EnCodec Live `title` field.
- Manifest titles now appear in Now Playing and Android media controls and
  replace URL-derived names in the persistent Saved livestreams menu.
- Livestream manifests without a title remain compatible and continue using
  the URL-derived fallback name.

## 0.10.0

- Added a separate persistent Saved livestreams menu. Opening a live manifest
  saves it automatically, and saved entries can be reopened or removed without
  mixing them into the finite-file playlist.
- Reduced playback UI work by lowering seek-position recomposition frequency
  from 10 to 2 updates per second.
- Throttled media-session and notification refreshes to meaningful state and
  one-second position changes instead of rebuilding them on every playback tick.

## 0.9.9

- Added a native `armeabi-v7a` build so the app can be installed on 32-bit ARM
  Android devices, while retaining the existing `arm64-v8a` and `x86_64`
  builds.

## 0.9.8

- Reduced live PCM pre-roll from one complete segment to the first approximately
  one-second EnCodec frame, restoring fast startup while still avoiding an
  empty `AudioTrack` start.
- Reduced live startup and rebuffer delays by beginning far enough behind the
  protected live edge to download three safe segments immediately from the
  current manifest.
- Increased the initial compressed live cushion from two to three segments.
- Reduced live-edge manifest polling from half a segment to one quarter of a
  segment, capped at one second, so newly published segments are discovered
  sooner without consuming the newest two protected entries.

## 0.9.7

- Reduced playback CPU wake-ups by replacing 5 ms polling against a full
  `AudioTrack` buffer with bounded blocking writes of approximately 20 ms.
- Retained non-blocking writes while paused so stop, track changes and seeking
  cannot become stuck behind an audio buffer that is not draining.
- Native EnCodec inference remains single-threaded with Eigen parallelism and
  OpenMP disabled in the Android build.

## 0.9.6

- Added EnCodec 24 kHz mono livestream support with automatic model, sample
  rate, channel, codebook and bitrate detection from `stream.json`.
- Live decoder and `AudioTrack` selection now follow the manifest variant
  instead of being hardcoded to the 48 kHz stereo HQ model.
- Live segment duration and ECDC initialization validation now support both
  24 kHz mono and 48 kHz stereo streams while continuing to reject LM coding.
- The live UI now reports the detected audio format.

## 0.9.5

- Added a bounded one-segment PCM pre-roll for live playback. This gives slower
  phones decoder headroom without restoring the multi-segment decoded queue
  that previously raced ahead of playback.
- Added direct byte-range seeking for remote 24 kHz mono ECDC files instead of
  downloading again from byte zero and walking the entire code stream.
- 24 kHz seeks include one preceding four-second code chunk so the decoder still
  receives its required causal warm-up context.
- Added equivalent direct seeking for seekable local 24 kHz document providers.

## 0.9.4

- Fixed intermittent live rebuffering caused by the background downloader
  racing through every entry in a cached manifest and waiting at its newest
  segment.
- The client now continuously keeps the newest two manifest segments as a live
  edge safety margin, rather than applying that offset only at startup.
- Compressed segments still download concurrently with decoding and remain in
  the bounded adaptive queue; played segments are not retained.

## 0.9.3

- Fixed a live playback regression that could consume downloaded segments ahead
  of actual AudioTrack playback, rapidly emptying the live queue and causing
  random pauses even on fast phones.
- Restored direct frame-by-frame decoding into AudioTrack while retaining the
  existing background downloader and adaptive compressed-segment buffer.
- Kept automatic `.ecdc` file and `.json` livestream URL detection from 0.9.2.

## 0.9.2

- Added a decoded-PCM live queue. Two downloaded segments are now decoded
  before AudioTrack starts, and up to three decoded segments are prepared in a
  background producer while playback consumes the current segment.
- Renamed the live queue indicator from "buffered" to "downloaded" so it no
  longer implies that the compressed segment count is decoded audio headroom.
- Replaced the separate file and livestream actions in Open URL with one Open
  action that selects `.ecdc` files or `.json` live manifests automatically.

## 0.9.1

- Fixed finite 24 kHz mono files being labeled as 48 kHz stereo in the
  playlist and Now Playing details even though their ECDC metadata had been
  detected correctly.
- File labels now derive their sample rate and mono/stereo layout from the
  selected EnCodec variant. HQ live streams remain labeled 48 kHz stereo.

## 0.9.0

- Replaced the ExecuTorch playback backend with the lightweight C++ EnCodec
  decoder from HenryDelMal's dual-model `encodec.cpp` fork.
- Added automatic 24 kHz mono and 48 kHz stereo model selection from ECDC
  metadata. Raw version-0 files remain limited to non-LM streams.
- Runs neural decoding on one reusable native worker with Eigen/NEON
  vectorization, avoiding OpenMP wakeups and duplicate model instances for a
  lower-power default on phones.
- Keeps only the active decoder model and matching `AudioTrack` resident when a
  playlist changes between 24 and 48 kHz material.
- Applies decoder-side peak rescaling to 0.99 after the ECDC normalization scale
  to prevent clipping.
- Uses bounded four-second chunks with one second of causal warm-up for long
  24 kHz files, preventing whole-file PCM accumulation and large-file crashes.
- Added C++ model-export instructions and vendored Eigen headers; PyTorch and
  ExecuTorch are no longer Android runtime dependencies.

## 0.8.11

- Restored the proven v0.8.10 playback and manifest-fetching architecture after
  the first adaptive-buffer implementation caused a playback regression.
- Live playback still starts with two buffered segments; after every actual
  rebuffer event, the refill target grows by one segment, up to six.
- Continues downloading and preparing upcoming manifest-listed segments in a
  background producer until the six-segment queue is full.
- Reuses every available segment in the current `stream.json` and requests a
  new manifest only after that list is exhausted. Initial selection remains two
  listed segments behind the live edge.
- Added a loading spinner plus current/target segment counts during initial
  buffering and recovery.

## 0.8.10

- Restored the ExecuTorch decoder to four inference threads after device timing
  confirmed approximately 190–260 ms per one-second HQ frame with substantial
  real-time headroom.
- Retained the Android 12+ low start-threshold fix responsible for the improved
  local and HTTPS startup/seek latency.
- Kept decoder timing in Logcat for performance comparisons on lower-range
  devices.

## 0.8.9

- Lowered the Android 12+ streaming `AudioTrack` start threshold from its
  decode-ahead capacity to the platform's minimum safe buffer, allowing sound
  to begin without priming seconds of PCM.
- Increased adaptive ExecuTorch inference parallelism from a hard four-thread
  ceiling to as many as six threads on higher-core-count devices.
- Added `EnCodecDecoder` Logcat timing for every decoder invocation to separate
  neural inference time from input and audio-output latency during testing.

## 0.8.8

- Reused one persistent `AudioTrack` for finite local files, static HTTPS files,
  seeking, track changes, and live streaming, matching the low-latency live
  playback architecture.
- Moved audio-device initialization into app startup alongside decoder preload.
- Flushes the shared track at every session boundary so stale queued PCM is
  discarded without reopening Android's audio output device.

## 0.8.7

- Removed a full-frame overlap pipeline stall that required two one-second HQ
  frames to finish decoding before the first PCM reached `AudioTrack`.
- Static and live playback now emit frame 1 immediately after its decoder call
  and retain only the final 10 ms required to crossfade with frame 2.
- Reduced startup and seek latency without removing EnCodec's segment-boundary
  crossfade or changing the ECDC bitstream.

## 0.8.6

- Discarded queued `AudioTrack` decode-ahead PCM immediately when stopping,
  seeking, or replacing a playback session instead of allowing stale audio to
  delay the next session.
- Began loading the HQ ExecuTorch decoder when the app opens so its one-time
  initialization overlaps track selection.
- Reduced static HTTPS startup buffering from roughly three seconds with a
  2 KiB floor to roughly one second with a 512-byte floor, substantially
  improving startup and range-seek latency for 3 kbps files.

## 0.8.5

- Kept the ExecuTorch HQ decoder loaded across normal playback, track changes,
  HTTPS playback, live playback, and seeking instead of reloading the model.
- Added direct compressed-frame seeking for local ECDC documents exposed
  through seekable Android file descriptors.
- Retained sequential local seeking as a compatibility fallback for virtual
  and cloud-backed document providers that expose non-seekable streams.
- Centralized exact HQ frame-offset calculation for local and HTTPS sources.

## 0.8.4

- Added exact HQ ECDC frame-offset calculation for static URL seeking.
- Static playback and seeking now use HTTP byte ranges instead of downloading
  the complete file or scanning from byte zero.
- Reduced EDGE startup to a bitrate-aware buffer of roughly three to six
  seconds while retaining up to 512 KiB of bounded download-ahead data.
- Made seek cancellation close the previous HTTP range immediately instead of
  waiting for its read timeout, and reused the validated ECDC header in memory.
- Started the new HTTP range concurrently with ExecuTorch decoder initialization
  so connection and initial buffering no longer wait for model setup.
- Started live manifest polling and segment prefetching before decoder
  initialization, allowing the two-segment live buffer to fill concurrently.

## 0.8.3

- Added bounded background download-ahead buffering for static HTTPS ECDC
  files, with a bitrate-aware startup cushion and up to 512 KiB held in memory.
- Kept fetching compressed audio while the decoder is running so temporary
  network stalls are less likely to starve `AudioTrack`.
- Preserved progressive playback: the complete remote file does not need to be
  downloaded before playback starts.
- Reduced startup latency for low-bitrate files and inspect new URLs with a
  1 KiB HTTP Range request before playback.

## 0.8.2

- Added a background queue of up to three downloaded, integrity-verified live
  segments and require two segments before starting live playback.
- Cached manifest windows so already-published segments are consumed without a
  redundant manifest request before every segment.
- Allowed fully consumed HTTP responses to reuse keep-alive sockets, reducing
  repeated DNS lookups and TLS handshakes.
- Added live buffer-depth and explicit rebuffering status in the player UI.
- Linked the companion
  [EnCodec Live Streamer](https://github.com/HenryDelMal/encodec-live-streamer)
  project in the livestream documentation.

## 0.8.1

- Changed the example live manifest URL into a placeholder so the URL field is
  empty and ready for typing when no real URL has previously been saved.

## 0.8.0

- Added mainstream EnCodec Live v1 playback from HTTP or HTTPS `stream.json`
  manifests through the existing Open URL workflow.
- Added strict manifest/codec validation, bounded downloads, byte-length and
  SHA-256 segment verification, redirect handling, cache bypass, and retry
  backoff.
- Added live-edge buffering, ordered sequence tracking without duplicates,
  cleanup-window recovery, and discontinuity detection for sequence gaps,
  epoch changes, and explicit markers.
- Reused one ExecuTorch decoder and one persistent `AudioTrack` across normal
  live segment boundaries while preserving ECDC frame overlap/crossfading.
- Added LIVE status, sequence, bitrate, codebook display, Reconnect, Jump to
  live, Stop, and Disconnect controls.
- Integrated live playback with the existing media session, notification,
  foreground service, CPU wake lock, and Wi-Fi lock.
- Preserved local files, finite HTTPS playback, playlists, persistence,
  seeking, shuffle, repeat, and background playback behavior.

The supported live codec remains HQ `encodec_48khz`, ECDC v0, 48 kHz stereo,
2/4/8/16 codebooks, with language-model entropy coding disabled.
