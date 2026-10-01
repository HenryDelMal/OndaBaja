cmake_minimum_required(VERSION 3.22.1)
project(vocos_android LANGUAGES CXX)

add_library(
    vocos_android
    SHARED
    vocos_jni.cpp
    vocos/vocos.cpp
)

target_compile_features(vocos_android PRIVATE cxx_std_20)
target_compile_definitions(vocos_android PRIVATE EIGEN_DONT_PARALLELIZE=1)
target_include_directories(
    vocos_android
    PRIVATE
    vocos
    third_party/eigen
)

# The decoder is the playback hot path even in a debuggable APK. Arm64 already
# enables NEON, and avoiding OpenMP keeps inference on one predictable worker.
target_compile_options(
    vocos_android PRIVATE
    -O3 -ffast-math -ffunction-sections -fdata-sections -fvisibility=hidden
)
target_link_options(vocos_android PRIVATE -Wl,--gc-sections)
target_link_libraries(vocos_android PRIVATE android log)

# Retain finite-value validation of checkpoint tensors and Vocos PCM even
# though the existing native target uses fast math for its neural hot path.
set_source_files_properties(vocos_jni.cpp vocos/vocos.cpp PROPERTIES
    COMPILE_OPTIONS "-fno-finite-math-only")
