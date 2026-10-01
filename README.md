# OndaBaja

Android app adapted from the EnCodec Android Player. OndaBaja loads its station catalog from a build-time configurable URL and offers a compact station list with live playback controls.

## Local build configuration

The repository-root `config.properties` file controls the station catalog URL, Vocos model asset and checksum, network timeouts, and live-buffer segment counts. Copy [`config.example.properties`](config.example.properties) to `config.properties` and modify the local copy before building. `config.properties` is ignored by Git and is not uploaded with the repository. Gradle reads it while building and embeds its values in the APK.

Without a local config file, the defaults match `config.example.properties`. The default catalog address is `https://radios.example.com/radio_list.json`, which is a placeholder. Set `catalog.url` to the actual hosted catalog before building an APK for users. The pretrained Vocos model is not stored in this repository. Download `pytorch_model.bin` from [Charactr's Vocos model repository](https://huggingface.co/charactr/vocos-encodec-24khz), install the exporter requirements, then convert it into the Android asset with `python tools/export_vocos.py /path/to/pytorch_model.bin app/src/main/assets/vocos-encodec-24khz-f32.bin`. The model file is ignored by Git. For a different compatible checkpoint, update both `decoder.modelAsset` and `decoder.modelSha256` in your local config.

The timeout settings are milliseconds. Catalog timeouts default to 12 seconds for connect and read. File requests default to 15/30 seconds. Live stream requests default to 4/5 seconds. Live buffering defaults are three segments for the rebuffer target and six segments for both the maximum buffer and startup cushion.

## Station catalog JSON (proposed)

The format is not finalized. A fictional example `radio_list.json` is in [`docs/radio_list.example.json`](docs/radio_list.example.json). The loader currently accepts a root `stations` or `streams` array. Each item needs `name` (or `title`) and `url` (or `streamUrl` / `stream_url`). Optional fields are `id`, `region` (or `location`), and `description`. Stream URLs must be HTTP or HTTPS. The example URLs use the reserved `example.com` domain and are placeholders, not playable stations.

Each station URL is expected to point to an EnCodec Live v1 manifest, not a conventional MP3/AAC radio stream. Vocos decodes EnCodec tokens; ordinary radio streams need a standard media player and do not use Vocos. The decoder is fixed to Vocos for compatible 24 kHz mono streams; 48 kHz and unsupported codebook counts are rejected.

## Build requirements

- Android Studio, Android SDK 35, JDK 21, Android NDK 27, and CMake 3.22.1.
- Before building, export the compatible float32 Vocos model to `app/src/main/assets/vocos-encodec-24khz-f32.bin` as described above. The model is intentionally excluded from Git. The APK packages the locally supplied asset. On first launch, the app copies it into private app storage and verifies its SHA-256. If an app update bundles a different model, its changed checksum triggers a fresh copy and replaces the previous copy.

Run `./gradlew :core:ecdc:test :app:testDebugUnitTest assembleDebug` to build.

## Smaller APK build

Use `:app:assembleRelease` for the APK intended for sideloading. Release code and resources are shrunk, unused native EnCodec code is omitted, and native libraries use compressed APK packaging. It includes all three supported architectures and the full, unchanged float32 Vocos model. The release build currently uses the development signing certificate; preserve it for sideloaded app updates.

The catalog parser accepts Markdown-wrapped URLs, but the server should use plain HTTP/HTTPS strings. Text is read as UTF-8. A corrected two-station catalog is in `outputs/emeradios-corrected.json`.

Missing or updated models show a preparation dialog while the app copies the bundled model and validates its SHA-256. The model remains unchanged float32 weights.

The copied EnCodec native implementation and its Kotlin decoder class were deleted. The packaged native library is now named `vocos_android`; ECDC parsing and the decoder interface remain because Vocos reads EnCodec tokens. Only the Vocos model and required license notices remain under assets. The original waveform branding was replaced with a radio icon.

## Licensing and source availability

Original application source code is licensed under the MIT License in [`LICENSE`](LICENSE). Third-party components retain their own licenses. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and the license texts in `app/src/main/assets/licenses/`.

The complete project source is available from the developer at no charge upon request while the source repositories are being prepared. Requests can be made through [GitHub](https://github.com/HenryDelMal/vocos.cpp/issues/new?title=Source%20code%20request). The source will be provided in a timely manner and at no more than the cost of delivery.
