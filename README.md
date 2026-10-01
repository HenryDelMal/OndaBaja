# OndaBaja

Android app adapted from the EnCodec Android Player. OndaBaja loads its station catalog from a build-time configurable URL and offers a simple station list with live playback controls.

## Local build configuration

The repository-root `config.properties` file controls the station catalog URL, Vocos model asset and checksum, network timeouts, and live-buffer segment counts. Copy [`config.example.properties`](config.example.properties) to `config.properties` and modify the local copy before building. `config.properties` is ignored by Git and is not uploaded with the repository. Gradle reads it while building and embeds its values in the APK.

Without a local config file, the defaults match `config.example.properties`. The default catalog address is `https://radios.example.com/radio_list.json`, which is a placeholder. Set `catalog.url` to the actual hosted catalog before building an APK for users. The pretrained Vocos model is not stored in this repository. Download `pytorch_model.bin` from [Charactr's Vocos model repository](https://huggingface.co/charactr/vocos-encodec-24khz), install the exporter requirements, then convert it into the Android asset with `python tools/export_vocos.py /path/to/pytorch_model.bin app/src/main/assets/vocos-encodec-24khz-f32.bin`. The model file is ignored by Git. For a different compatible checkpoint, update both `decoder.modelAsset` and `decoder.modelSha256` in your local config.

The timeout settings are milliseconds. Catalog timeouts default to 12 seconds for connect and read. File requests default to 15/30 seconds. Live stream requests default to 4/5 seconds. Live buffering defaults are three segments for the rebuffer target and six segments for both the maximum buffer and startup cushion.

## Station catalog JSON (proposed)

The format is not finalized. A fictional example `radio_list.json` is in [`docs/radio_list.example.json`](docs/radio_list.example.json). The loader currently accepts a root `stations` or `streams` array. Each item needs `name` (or `title`) and `url` (or `streamUrl` / `stream_url`). Optional fields are `id`, `region` (or `location`), and `description`. Stream URLs must be HTTP or HTTPS. The example URLs use the reserved `example.com` domain and are placeholders, not playable stations.

Each station URL is expected to point to an EnCodec Live v1 manifest, not a conventional MP3/AAC radio stream. Vocos decodes EnCodec tokens; ordinary radio streams need a standard media player and do not use Vocos. The decoder is fixed to Vocos for compatible 24 kHz mono streams; 48 kHz and unsupported codebook counts are rejected.

For a server implementation that provides EnCodec Live streams to this app, see [EnCodec Live Streamer](https://github.com/HenryDelMal/encodec-live-streamer).

## Build requirements

- Android Studio, Android SDK 35, JDK 21, Android NDK 27, and CMake 3.22.1.
- Before building, export the compatible float32 Vocos model to `app/src/main/assets/vocos-encodec-24khz-f32.bin` as described above. The model is intentionally excluded from Git. The APK packages the locally supplied asset. On first launch, the app copies it into private app storage and verifies its SHA-256. If an app update bundles a different model, its changed checksum triggers a fresh copy and replaces the previous copy.

Run `./gradlew :core:ecdc:test :app:testDebugUnitTest assembleDebug` to build.

## Licensing and source availability

Original application source code is licensed under the MIT License in [`LICENSE`](LICENSE). Third-party components retain their own licenses. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and the license texts in `app/src/main/assets/licenses/`.

The complete project source is published in the [OndaBaja GitHub repository](https://github.com/HenryDelMal/OndaBaja) under the MIT License. Third-party components retain their own licenses; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
