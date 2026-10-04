# OndaBaja

Android app adapted from the EnCodec Android Player. OndaBaja loads its station catalog from a build-time configurable URL and offers a simple station list with live playback controls.

## Local build configuration

The repository-root `config.properties` file controls the station catalog URL, Vocos model asset and checksum, network timeouts, and live buffering settings. Copy [`config.example.properties`](config.example.properties) to `config.properties` and modify the local copy before building. `config.properties` is ignored by Git and is not uploaded with the repository. Gradle reads it while building and embeds its values in the APK.

Without a local config file, the defaults match `config.example.properties`. The default catalog address is `https://radios.example.com/radio_list.json`, which is a placeholder. Set `catalog.url` to the actual hosted catalog before building an APK for users. The pretrained Vocos model is not stored in this repository. Download `pytorch_model.bin` from [Charactr's Vocos model repository](https://huggingface.co/charactr/vocos-encodec-24khz), install the exporter requirements, then convert it into the Android asset with `python tools/export_vocos.py /path/to/pytorch_model.bin app/src/main/assets/vocos-encodec-24khz-f32.bin`. The model file is ignored by Git. For a different compatible checkpoint, update both `decoder.modelAsset` and `decoder.modelSha256` in your local config.

The timeout settings are milliseconds. Catalog timeouts default to 12 seconds for connect and read. File requests default to 15/30 seconds. Live stream requests default to 1/2 seconds, with a 3-second total deadline. `live.bufferTargetMs=30000` sets a background prefetch goal of thirty seconds, rounded up to whole segments. `live.maxBufferSegments=12` limits the queue. `live.startupLookbackMs=30000` selects older manifest-listed audio so the app can build that reserve while playback begins immediately. For an eight-segment window of five-second files, playback starts at the third-oldest segment and downloads the next five in the background. Windows of at least eight segments leave their oldest two files unused at startup to allow time before server deletion. Downloaded audio remains usable after the server deletes its file. Shorter windows use the audio they offer, so they provide less protection against outages. After an actual underrun, the recovery buffer grows and playback resumes after several segments are ready. Recovery refreshes the manifest and never replays already accepted sequences. Replace the former `live.rebufferTargetSegments` setting with `live.bufferTargetMs` in existing local configs.

Advanced settings provide `Default`, `HTTP/3`, `HTTP/2`, `HTTPS`, and `Plain HTTP`. `Default` prefers Cronet HTTP/3, then HTTP/2, then Android's standard HTTPS connection. `HTTP/3` and `HTTP/2` use the corresponding Cronet transport with secure fallbacks. `HTTPS` uses Android's standard HTTPS connection. `Plain HTTP` uses unencrypted HTTP. Switching between encrypted and cleartext modes rewrites `http://` and `https://` consistently for the catalog, manifests, redirects, and segments. Cronet's native engine is supplied by Google Play services and is not bundled in the APK. Devices without the provider use standard HTTPS for secure modes. Protocol and fallback choices are recorded in debug logs.

After a gap, playback resumes with two ready segments. If only one is ready, it waits at most one quarter of a segment duration, capped at one second, before resuming. The larger background buffer continues filling independently. No recovery delay is counted before any playable segment is ready.

## Station catalog

The app prefers the protobuf directory and falls back to JSON if its `.pb` sibling cannot be fetched or decoded. Configure either catalog URL in `config.properties`; a `.json` URL is converted to `.pb` by replacing its final extension, and a `.pb` URL gets the matching `.json` fallback. The fictional JSON example is in [`docs/radio_list.example.json`](docs/radio_list.example.json). Both formats use a root `version` and `stations` array. Each station has `id`, `name`, and an HTTP or HTTPS EnCodec Live manifest `url`. The optional `protobuf` flag indicates whether that station publishes a protobuf live manifest. Optional `tcp` and `tcp_url` advertise ELTCP support. ELTCP is enabled by default. OndaBaja uses it for stations with `tcp: true` and a valid `tcp_url`; users can disable it in **Settings → Advanced**. It falls back to HTTP/HTTPS only if the initial TCP connection cannot be established. Once connected, it reconnects over TCP after a drop and resumes from a fresh manifest. Initial TCP connect timeout is 1.5 seconds, reconnect connect timeout is 2 seconds, segment read timeout is 3 seconds, and manifest read timeout is 18 seconds when no server heartbeat has been observed. After a server heartbeat is observed during a manifest poll, the client uses a 7.5-second inactivity timeout, resetting it as each heartbeat arrives, so a stalled connection is detected sooner. It sends a one-byte `h` echo probe between requests every 30 seconds when it has not received a heartbeat, and waits up to 1.5 seconds for an echo. If a segment response times out before its first byte, the client sends up to three echo probes, handles any delayed segment response that precedes the echo, then retries that segment up to twice on the same socket. For a manifest that times out after heartbeats have been observed, it reconnects without probing while the manifest request is pending. Without an observed heartbeat, a manifest poll that exceeds 18 seconds uses the existing-socket recovery probe path. Timeouts after a framed response has begun still close the socket to avoid corrupting the stream. The server may echo client probes with periodic heartbeats disabled; enable unsolicited heartbeats only after testing the client against them. ELTCP is unencrypted and sends no compression; use it only with a trusted server/network.

### Converting a station directory to protobuf

The station-directory protobuf is separate from the per-station `stream.pb` live-audio manifest. Its schema is [`proto/station_directory.proto`](proto/station_directory.proto). Convert a JSON directory with Python 3 (no third-party packages required):

```bash
python3 tools/json_to_station_pb.py radio_list.json radio_list.pb
```

The input root has `version` and a `stations` array. Each station requires `id`, `name`, and `url`; `protobuf` and `tcp` are optional booleans, and `tcp_url` is optional but required when `tcp` is `true`. Explicit `false` values are preserved in the protobuf. Fields such as the old `region` and `description` are no longer part of this catalog schema.

Each station URL is expected to point to an EnCodec Live v1 manifest, not a conventional MP3/AAC radio stream. Vocos decodes EnCodec tokens; ordinary radio streams need a standard media player and do not use Vocos. The decoder is fixed to Vocos for compatible 24 kHz mono streams; 48 kHz and unsupported codebook counts are rejected.

ELTCP playback begins with `i` and a protobuf manifest. Servers supporting absolute-sequence fetch then receive `f` plus the absolute sequence for each segment and return an `S` frame containing its length, big-endian CRC32C, discontinuity flags, and ECDC bytes. `n` retries the same sequence after one second; `g` triggers an immediate `i` manifest refresh. CRC failures send `e` only after a complete frame has been read. If the server answers `p`, the client reconnects and uses the legacy `m`/`s` requests for the rest of that playback session. Every TCP reconnection starts with `i` and obtains a fresh manifest.

For a server implementation that provides EnCodec Live streams to this app, see [EnCodec Live Streamer](https://github.com/HenryDelMal/encodec-live-streamer).

## Build requirements

- Android Studio, Android SDK 35, JDK 21, Android NDK 27, and CMake 3.22.1.
- Before building, export the compatible float32 Vocos model to `app/src/main/assets/vocos-encodec-24khz-f32.bin` as described above. The model is intentionally excluded from Git. The APK packages the locally supplied asset. On first launch, the app copies it into private app storage and verifies its SHA-256. If an app update bundles a different model, its changed checksum triggers a fresh copy and replaces the previous copy.

Run `./gradlew :core:ecdc:test :app:testDebugUnitTest assembleDebug` to build.

## Licensing and source availability

Original application source code is licensed under the MIT License in [`LICENSE`](LICENSE). Third-party components retain their own licenses. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and the license texts in `app/src/main/assets/licenses/`.

The complete project source is published in the [OndaBaja GitHub repository](https://github.com/HenryDelMal/OndaBaja) under the MIT License. Third-party components retain their own licenses; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Live playback diagnostics

Enable **Configuración > Avanzado > Registros de depuración** before reproducing a problem. Capture from station selection through startup and a dropout:

```bash
adb logcat -c
adb logcat -v threadtime EnCodecLive:I EnCodecDecoder:I '*:S' > ondabaja-startup.log
```

Stop the capture with Ctrl+C. Logs identify the station/session, HTTP request ID and path (without query parameters), response headers and body-read timing, retries, manifest ranges and refresh/poll reasons, compressed queue depth and audio duration, producer pacing, decoder time, queued PCM, playback head, and AudioTrack underruns. `play segment` records decoded PCM submission, not the instant all of that audio has reached the speaker. An empty compressed queue may occur while AudioTrack still has playable PCM; its queue and underrun counters help distinguish that from audible starvation.

Advanced settings let users choose preferred HTTP compression: Default, Brotli, GZip, or Uncompressed. Default advertises Brotli first, GZip second, and identity as the final fallback. Catalog, manifest, segment, and full-file requests use that preference across plain HTTP, HTTPS, HTTP/2, and HTTP/3. The client decodes both Brotli and GZip responses. Byte-range requests use identity encoding so offsets remain valid. Cronet has Brotli enabled for its HTTP/2 and HTTP/3 engines. Cronet completion logs report the negotiated protocol (`h3`/`h2`) and response `contentEncoding` to confirm the encoding actually returned.

`payloadBytes` is the decoded response body size. For Brotli or gzip responses, `compressedBytes` is the actual response body size counted before decompression; `wireBodyBytes` also reports the response body count for uncompressed responses. These counts exclude HTTP headers and TCP/TLS/IP overhead. Request, header, completion, and retry lines include the effective `scheme=http|https`. Cleartext requests are marked `transport=plain_http`; encrypted requests show their selected or negotiated transport, so `mode=HTTP` is not the only indication of cleartext use. UID receive/transmit deltas cover the whole app and are approximate. `pool_allowed` permits connection reuse; it does not prove that a socket was reused or separately measure DNS/TCP/TLS/IP overhead. Debug logs also rotate in private app storage for outages. The release APK is not debuggable, so `adb shell run-as` cannot read that private file; use the logcat capture above.
