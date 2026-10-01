# Android build context

Source chat: Build Android EnCodec decoder (01a027db-dab8-79b3-8e20-23e625dde39f).
The source app built successfully with Java 21 after temporarily overriding all four module toolchains. EmergencyRadioCL retains jvmToolchain(21) in all modules.

JDK: /Users/henry/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
Gradle 8.9; Android Gradle plugin 8.7.3; Kotlin 2.0.21.
SDK/NDK paths: local.properties. Gradle cache: ~/.gradle.

Build from EmergencyRadioCL:

    JAVA_HOME=/Users/henry/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleRelease --no-daemon

Release shrinks code/resources and compresses native libraries. CMake builds only the Vocos native decoder. All three original ABIs remain supported. The release APK uses the same development certificate as the previous EmergencyRadioCL debug APK, allowing sideload updates.

The historical build used `app/src/main/assets/vocos-encodec-24khz-f32.bin` (40,328,571 bytes). That pretrained model is intentionally not tracked in this repository. For a fresh build, download `pytorch_model.bin` from [Charactr's Vocos model repository](https://huggingface.co/charactr/vocos-encodec-24khz) and convert it with `tools/export_vocos.py` to the Android asset path. The APK includes the locally supplied model; on first launch or when its bundled checksum changes, the app copies it into private storage and verifies SHA-256. CMake builds only vocos_android; the unused EnCodec implementation is deleted. PlaybackService is stopped and its foreground notification explicitly removed on shutdown; stale service notifications are cleared when PlayerViewModel is created.

Verified on 2026-10-01: release build and APK contents for the original model. No Android device was connected; on-device model copy, notification cleanup, and playback remain unverified.

Deliverable: outputs/EmergencyRadioCL-v0.1.2.apk, 39,297,374 bytes (37.48 MiB). The original 40,328,571-byte model is deflated by APK packaging to 37,533,388 bytes. No zstd-jni library is included.
Corrected catalog: outputs/emeradios-corrected.json. Parser accepts Markdown URLs; server should supply plain UTF-8 JSON URLs.

The model weights are the original float32 bytes with no model-level compressor or quantization. APK ZIP packaging reduces the model asset to 37,533,388 bytes. The APK includes one Vocos model and the three supported ABIs, with no zstd-jni or legacy EnCodec decoder library. First-use dialog awaits successful background copy and offers retry on error. Playback-service teardown explicitly removes its foreground notification, and PlayerViewModel clears stale service notifications at startup; device behavior remains unverified.
