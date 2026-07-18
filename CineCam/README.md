# CineCam

A "Blackmagic-style" cinema camera app for Android, built around the Samsung Galaxy A54's
Camera2 sensor. Manual exposure, a real-time GLSL LOG/LUT grading pipeline, high-bitrate
H.264/H.265 recording, manual audio gain, and a waveform/histogram monitor — all running live
on-device, matching what you see in the viewfinder to what gets recorded.

## Why Camera2 (not CameraX)

CameraX's `Camera2Interop` only exposes a subset of `CaptureRequest` keys and fights a
per-frame manual pipeline. This app talks to `CameraManager`/`CameraCaptureSession` directly so
every frame can carry fully manual `SENSOR_SENSITIVITY` (ISO), `SENSOR_EXPOSURE_TIME` (shutter),
`LENS_FOCUS_DISTANCE`, and `COLOR_CORRECTION_GAINS` (white balance) — the raw controls a "pro
camera" feel requires.

## Feature summary

| Spec item | Where it lives |
|---|---|
| Manual ISO / shutter angle / white balance (Kelvin) / manual focus | `camera/CameraController.kt`, `camera/WhiteBalanceUtils.kt` |
| Flat/LOG-style picture profile | GLSL tone curve in `gl/CameraFilterProgram.kt` |
| `.cube` LUT loading + real-time application | `gl/CubeLut.kt` (parser + 2D tile-strip texture upload), sampled in the same fragment shader |
| Focus peaking / exposure zebras | Same fragment shader (Sobel-ish luma edge detect / gl_FragCoord stripe pattern) |
| High-bitrate H.264/H.265 recording, selectable codec/bitrate | `encoder/VideoEncoderCore.kt`, `encoder/RecordingController.kt` |
| Manual audio gain + AAC encoding | `audio/AudioCaptureThread.kt`, `encoder/AudioEncoderCore.kt` |
| Waveform / RGB parade histogram overlay | `overlay/WaveformHistogramAnalyzer.kt` + `overlay/WaveformHistogramOverlay.kt` |

## Architecture

```
Camera2 (CameraController)
   → SurfaceTexture (owned by CameraGLRenderThread)
       → GLSL pass (CameraFilterProgram: LOG curve → LUT → peaking → zebras)
           ├── preview WindowSurface (on-screen SurfaceView)
           ├── encoder WindowSurface (VideoEncoderCore's MediaCodec input surface)
           └── downsampled offscreen pbuffer → glReadPixels → WaveformHistogramAnalyzer

AudioCaptureThread (AudioRecord + manual gain) → AudioEncoderCore (AAC)
                                                        ↘
VideoEncoderCore (H.264/H.265) ───────────────────────→ MuxerWrapper → MediaStore (Movies/CineCam)
```

One EGL context (`gl/EglCore.kt`, `gl/WindowSurface.kt`) renders every camera frame once and
fans it out to however many destinations are currently active (preview always; encoder + the
monitor pbuffer only while relevant), so the recorded file always matches what's on screen —
no separate "record path" with different processing.

True sensor-level LOG/RAW capture isn't exposed by Android's camera stack on this class of
hardware, so "LOG" here means a calibrated flat tone curve applied in the shader — the same
approach real-time LUT-preview tools use, not scene-referred RAW.

## Project layout

```
app/src/main/java/com/voxenai/cinecam/
  camera/    Camera2 session + manual CaptureRequest control
  gl/        EGL core, GLSL filter program, .cube LUT parser/loader, GL render thread
  encoder/   MediaCodec video/audio encoders, muxer, recording orchestration
  audio/     Mic capture + manual gain
  overlay/   Waveform/histogram analysis + Compose drawing
  ui/        Compose screens, controls, ViewModel
app/src/main/assets/luts/   Bundled sample .cube LUTs
```

## Building

Requires Android Studio (Koala+) with Android SDK Platform 34 and a device/emulator on
API 26+ with Camera2 `MANUAL_SENSOR` support (the Galaxy A54's main camera qualifies).

```
./gradlew :app:assembleDebug
```

> This project was scaffolded in a sandboxed CI environment without access to
> `dl.google.com` (Android Gradle Plugin / SDK) or the Gradle distribution service, so it could
> not be built or run here. The Gradle wrapper jar is intentionally not committed for that
> reason — run `gradle wrapper --gradle-version 8.7` once (or just open the project in Android
> Studio, which bootstraps it automatically) before your first build. Every file was written and
> manually cross-checked against the Camera2/MediaCodec/Compose APIs it calls, but you should
> still expect to do a first real build-and-run pass on a device before treating this as
> production-ready.

## Known limitations / next steps

- Frame-rate/exposure-time validation trusts `CameraCharacteristics` ranges but doesn't probe
  per-resolution `StreamConfigurationMap` min frame durations — very high frame rates at 4K may
  get clamped by the driver rather than rejected up front.
- LUT application uses the standard "2D tile-strip" trick (two texture fetches + a manual blend)
  for GLES2 compatibility rather than `GL_TEXTURE_3D`; fine for the LUT sizes cinema LUTs
  typically ship at (17/33/65) but not free — a GLES3 path using real 3D textures would be a
  reasonable follow-up.
- No external USB-C/3.5mm mic device picker yet; `AudioRecord` uses `CAMCORDER` falling back to
  `MIC`, which already prefers a wired/USB mic over the built-in one when the OS routes it that
  way, but there's no in-app UI to choose explicitly.
- No zoom, no multi-cam (wide/ultrawide/tele) switching yet.
