# AutoTap

A small Android automation app (Kotlin) that walks through *another* app by
tapping a target point, waiting for the next screen to render, and capturing
each screen — then stitches all the captured frames into one tall image and
saves it to **both** the system gallery and the app (viewable / shareable).

Works on any target app (the tap target is placed by you at runtime).

## Features

1. **Accessibility Service** (`AutoTapAccessibilityService`) — can tap a UI
   element by text, or dispatch a raw `dispatchGesture` at fixed coordinates.
2. **Screen capture** (`ScreenCaptureService`) — `MediaProjection` +
   `ImageReader` in a foreground service.
3. **Loop logic** — a question counter, a per-tap delay, and the
   `capture → tap → wait → capture` cycle.
4. **Storage + stitching** (`FrameStitcher`) — each frame saved as a PNG, then
   stacked vertically on a `Canvas` (with page-splitting so long captures don't
   run out of memory).
5. **Place target** — the floating overlay has a **Place target** button: tap
   anywhere on screen (e.g. the "Next" button) and a draggable red marker is
   saved as the tap point.
6. **Gallery + share** — the final stitched image is written to the system
   gallery (`Pictures/AutoTap` via `GallerySaver`) and also available in-app
   (`ResultsActivity`) to open in any viewer or share.
7. **Overlay controls (optional)** — floating start/stop + place-target
   (`OverlayService`, needs `SYSTEM_ALERT_WINDOW`).

## Project layout

```
app/src/main/
  AndroidManifest.xml            permissions + service/activity/provider declarations
  res/xml/accessibility_service_config.xml
  res/xml/file_paths.xml         FileProvider paths for stitched output
  res/layout/activity_main.xml   configuration UI
  res/layout/activity_results.xml in-app viewer
  res/layout/overlay_controls.xml floating controls
  java/com/autotap/app/
    MainActivity.kt              UI, permission flow, glue
    ResultsActivity.kt           in-app viewer for stitched output
    AutoTapAccessibilityService.kt
    ScreenCaptureService.kt      MediaProjection + capture + loop
    OverlayService.kt            floating controls + draggable "place target" marker
    GallerySaver.kt              writes stitched PNGs into the system gallery
    FrameStitcher.kt            Canvas-based stitching
    ImageUtil.kt                Image -> Bitmap
    Config.kt                   run config + persistence
```

## Build & run

Open this folder in Android Studio, or from the folder:

```
./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

> Note: Android's resource compiler (AAPT2) is only published by Google as
> x86-64 (and Apple-Silicon) binaries. Build on x86-64 Linux/Windows, macOS
> (Intel), or an Apple Silicon Mac — not on arm64 Linux.

## Permissions & manual consent

Android requires the user to approve these by hand (OS-level protection):

- **Accessibility** — Settings → Accessibility → AutoTap → enable.
- **Screen capture** — the system dialog shown when you press *Start Capture*.
- **Overlay (optional)** — Settings → Apps → AutoTap → Draw over other apps.

## Using it

1. Enter the button **text** to tap (e.g. `Next`), the **number of questions**
   (tap cycles), and the **delay** after each tap (ms).
2. *Enable Accessibility Service* → turn AutoTap on in system settings.
3. *Show Floating Controls* (if not already shown), then tap **Place target**
   and tap the on-screen button you want automated (e.g. "Next"). A red marker
   appears — drag it to fine-tune. This is the point AutoTap will click.
4. *Start Capture* → approve the screen-capture dialog.
5. Switch to the target app. AutoTap taps the placed point, waits, captures,
   and repeats until the counter is reached, then stitches the frames.
6. The final stitched image is saved to **both** the system gallery
   (Pictures/AutoTap) **and** the app — open it from **View Capture Results**
   (in-app viewer, share, or open in any viewer).

To tap by fixed coordinates instead of by text, tick *Tap at fixed coordinates*
and enter X/Y (current screen density pixels), or use the Place-target marker.

## Notes / limits

- Google Play restricts apps whose main purpose is automating other apps' UIs;
  sideloading avoids that review. Respect each target app's terms of service.
- Stitching downscales frames to a max width (default 1080px) and splits the
  result into pages when it would exceed ~12000px tall, to bound memory.
- `minSdk 23` / `targetSdk 34`.
