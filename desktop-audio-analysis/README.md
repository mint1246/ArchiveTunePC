# Desktop audio analysis

This module provides ArchiveTune's in-process Windows audio-analysis binding.

The native DLL statically links a deliberately small Essentia build using KISS FFT. It includes only the transitive algorithms required for:

- fast Degara beat tracking / beat timestamps;
- key + major/minor estimation;
- EBU R128 integrated loudness and loudness range.

There is no Python runtime, FFmpeg, TensorFlow, ONNX Runtime, separate worker process, or network API in this path. The Kotlin side uses JNA function calls and opaque native handles rather than JNA `Structure` reflection, which keeps the ABI simple for GraalVM Native Image.

The Windows native build is pinned to Essentia commit `66a890f285d0e1988155c12d17a2068e406cdd90` and Eigen commit `3147391d946bb4b6c68edd901f2add6ac1f31f8c`.

Essentia is AGPL-3.0-or-later. The build copies its license beside the generated DLL.
