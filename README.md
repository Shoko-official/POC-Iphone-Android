# POC-Iphone-Android

Android computational photography proof of concept: Kotlin, Jetpack Compose, CameraX 1.5.

## Capture modes

- **Photo** — every shutter press runs the full multi-frame pipeline: a full-resolution burst merged with subject-robust global alignment (per-tile median voting), per-tile sub-pixel refinement and dual-band ghost rejection, then finished. A developer toggle restores unprocessed single-shot capture for comparison.
- **Portrait** — burst merge, on-device subject segmentation (MLKit), mask-driven bokeh with edge-aware feathering, highlight bloom and subject-bleed prevention.
- **Video** — up to 4K (capability-gated quality selector), 10-bit HLG when the device supports it.
- **Cinematic** — 24 fps with video stabilization when available, GPU LUT color looks applied live to preview and recording.

## Processing pipeline

Pure Kotlin, deterministic, fully unit-tested:

- Burst merge: pyramid alignment robust to moving subjects, per-tile sub-pixel refinement, dual-band (raw + low-pass) ghost rejection.
- Exposure-bracketed HDR with Laplacian pyramid fusion.
- Night mode: 12-frame high-noise merge, gated on a walking-subject motion golden.
- 2x multi-frame super-resolution (kernel splatting on sub-pixel offsets).
- Finishing: adaptive backlit rescue (histogram-detected, guided local exposure lift), bounded-analysis auto white balance, luma-guided chroma denoise, guided local tone mapping, halo-controlled detail enhancement, filmic tone curve, spatially-gated chroma roll-off, semantic sky/foliage rendering (incl. overcast detection), skin-tone protection validated across the full skin-tone range.
- The default Natural preset is a reference-matched profile fitted against real side-by-side captures.
- Native-resolution (12 MP+) processing via seam-free tiled execution with width-adaptive halos; hot paths row-parallel with bit-identical output; a shared luma plane and per-stage timing hooks keep the chain measured (3 MP finish under 1 s on desktop reference hardware).

Quality is regression-gated in CI: PSNR/SSIM/MAE golden floors on synthetic scenes (fidelity and rendition axes), plus dedicated HDR, night, night-motion, super-resolution, AWB, bokeh, backlit, chroma roll-off, semantic-region and skin-fairness gates. Metrics for every build are published as a CI artifact.

## App features

Tap to focus/expose, pinch zoom, flash and torch, front camera with mirrored selfie saves, volume-key shutter with haptics, processing queue with stage indicator, last-capture thumbnail, quality presets (Natural / Vivid / Detail), HDR burst / night / super-resolution toggles, a manual backlit-rescue override (forces the shadow lift on for backlit shots the automatic detector would otherwise miss), EXIF on processed outputs, and an in-app A/B comparison viewer with reference import and on-device PSNR/SSIM/MAE.

Camera state (zoom, flash, mode, lens, look) and the settings survive navigation and rotation.

## Build

```
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # ~700 JVM tests incl. golden gates
./gradlew connectedDebugAndroidTest  # instrumented smoke tests (device/emulator)
./gradlew assembleRelease        # minified release (arm64-v8a)
```

Requires JDK 21 and the Android SDK (compileSdk 36, minSdk 26). CI runs build, lint, unit tests, instrumented smoke tests on an emulator, and a release build on every merge.
