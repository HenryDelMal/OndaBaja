# Third-party notices

The original OndaBaja source code is licensed under the MIT
License in `LICENSE`. Components listed below retain their own licenses.
Full license texts are included in `app/src/main/assets/licenses/` and in the
About screen of the application.

## Meta EnCodec

EnCodec is the source codec and token format for the live audio decoded by this
application. EnCodec is distributed under the MIT License. Copyright (c) Meta
Platforms, Inc. and affiliates. See `licenses/EnCodec-Meta-MIT.txt`.

## Vocos

This application uses the Vocos architecture and components from
[gemelo-ai/vocos](https://github.com/gemelo-ai/vocos), together with components
published by Charactr at
[charactr/vocos-encodec-24khz](https://huggingface.co/charactr/vocos-encodec-24khz).
The applicable project and model license is MIT. Copyright (c) 2023 Charactr
Inc. See `licenses/Vocos-MIT.txt`.

## Vocos.cpp

The native Vocos decoder is maintained by Henry Del Mal at
[HenryDelMal/vocos.cpp](https://github.com/HenryDelMal/vocos.cpp) and is
licensed under the MIT License. Its implementation is inspired by
[HenryDelMal/encodec.cpp](https://github.com/HenryDelMal/encodec.cpp), which is
based on [pfeatherstone/encodec.cpp](https://github.com/pfeatherstone/encodec.cpp).
See `LICENSE` for the Vocos.cpp project license and `licenses/encodec-MIT.txt`
for the encodec.cpp attribution license. The encodec.cpp repositories are
credited as inspiration; their native decoder sources are not compiled into
this application's active Android library.

## Eigen and its FFT implementation

The native decoder vendors Eigen headers and uses Eigen's FFT implementation.
These source files are under the Mozilla Public License 2.0. The included FFT
implementation is derived from KissFFT and retains Mark Borgerding's attribution
in its source header. See `licenses/Eigen-MPL2.txt`.

The upstream [KissFFT project](https://github.com/mborgerding/kissfft) is under
the BSD 3-Clause license. This notice identifies the upstream project; the
application uses the Eigen implementation described above, not a separate
KissFFT library. See `licenses/BSD-3-Clause.txt`.

## AndroidX and Jetpack Compose

The app uses AndroidX Activity, Lifecycle, Compose UI and Material 3 libraries.
These libraries are distributed under the Apache License 2.0. See
`licenses/Apache-2.0.txt` and the
[AndroidX project](https://github.com/androidx/androidx).

## Kotlin and kotlinx.coroutines

The app is written in Kotlin and uses kotlinx.coroutines. These projects are
distributed under the Apache License 2.0. See `licenses/Apache-2.0.txt`, the
[Kotlin project](https://github.com/JetBrains/kotlin), and the
[kotlinx.coroutines project](https://github.com/Kotlin/kotlinx.coroutines).

## Source code availability

The complete application source is published in the
[OndaBaja repository](https://github.com/HenryDelMal/OndaBaja) under the MIT
License. Third-party components retain their respective licenses as described
above.
