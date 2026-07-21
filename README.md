# POC-Iphone-Android

Android computational photography proof of concept: Kotlin, Jetpack Compose, CameraX 1.5.

## Capture modes

- **Photo** — single shot, plus multi-frame burst: full-resolution frames merged with sub-pixel tile alignment, per-pixel ghost rejection and noise-adaptive weighting.
- **Portrait** — burst merge, on-device subject segmentation (MLKit), mask-driven bokeh with edge-aware feathering, highlight bloom and subject-bleed prevention.
- **Video** — up to 4K (capability-gated quality selector), 10-bit HLG when the device supports it.
- **Cinematic** — 24 fps with video stabilization when available, GPU LUT color looks applied live to preview and recording.

## Processing pipeline

Pure Kotlin, deterministic, fully unit-tested:

- Burst merge: pyramid alignment, per-tile sub-pixel refinement, robust ghost rejection.
- Exposure-bracketed HDR with Laplacian pyramid fusion.
- Night mode: 12-frame high-noise merge with motion-adaptive weighting.
- 2x multi-frame super-resolution (kernel splatting on sub-pixel offsets).
- Finishing: auto white balance, luma-guided chroma denoise, guided local tone mapping, halo-controlled detail enhancement, filmic tone curve, skin-tone protection validated across the full skin-tone range.
- Native-resolution (12 MP+) processing via seam-free tiled execution; hot paths row-parallel with bit-identical output.

Quality is regression-gated in CI: PSNR/SSIM/MAE golden floors on synthetic scenes (fidelity and rendition axes), plus dedicated HDR, night, super-resolution, AWB, bokeh and skin-fairness gates. Metrics for every build are published as a CI artifact.

## App features

Tap to focus/expose, pinch zoom, flash and torch, front camera with mirrored selfie saves, volume-key shutter with haptics, processing queue with stage indicator, last-capture thumbnail, quality presets (Natural / Vivid / Detail), HDR burst / night / super-resolution toggles, EXIF on processed outputs, and an in-app A/B comparison viewer with reference import and on-device PSNR/SSIM/MAE.

## Build

```
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # ~520 JVM tests incl. golden gates
./gradlew connectedDebugAndroidTest  # instrumented smoke tests (device/emulator)
./gradlew assembleRelease        # minified release (arm64-v8a)
```

Requires JDK 21 and the Android SDK (compileSdk 36, minSdk 26). CI runs build, lint, unit tests, instrumented smoke tests on an emulator, and a release build on every merge.
