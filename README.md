# GhostBuffer

**GhostBuffer** is an open-source, pre-trigger rolling buffer camera application for Sony PlayMemories Camera Apps (PMCA) compatible mirrorless cameras, specifically targeting the **Sony α5000 (ILCE-5000)**.

> **Status (October 2026):** All 10 architectural and lifecycle remediations from the V1 review have been fully implemented and verified with 28 automated unit tests. The build produces a clean, runnable APK targeting Android 2.3.3 (Gingerbread API 10). See [V1 Review & Remediations](#v1-review--remediations) for detailed resolution of each finding.

---

## Overview

Traditional cameras only begin recording after the shutter is pressed, often missing spontaneous, high-speed, or unpredictable moments (e.g., lightning strikes, wildlife actions, sports milestones).

GhostBuffer solves this by maintaining a continuous rolling ring buffer in memory. The application polls the live preview stream at a target rate of 10 FPS, retaining up to **2000 ms (2 seconds)** in RAM. When you press the physical shutter button, the pre-trigger buffer is instantly frozen and persisted to your SD card.

---

## Features

- **Rolling Pre-Trigger Ring Buffer:**
  - Continuously retains up to 2 seconds of high-speed preview frames in RAM.
  - Strict byte-budget enforcement: rejects oversized incoming frames without corrupting the buffer, maintaining a 3 MiB JPEG payload budget and a 25-frame limit.
  - Time-window head eviction ensures oldest frames roll over seamlessly.

- **Zero Shutter Lag & Latching:**
  - Direct hardware shutter hook (`ISV_KEY_S2`).
  - Hardware repeat suppression prevents accidental multi-triggers when holding down the button.
  - Thread-safe snapshot freezing (`freezeSnapshot()`) decouples capture from background disk I/O.

- **Failsafe Storage Architecture:**
  - Saves sessions to `<SD Card>/GHOSTBUFFER/captures/<YYYYMMDD_HHmmss_SSS>/`.
  - Atomic frame writing: images are written as temporary files (`.tmp`) and synced (`fsync`) before renaming to prevent corrupt files upon sudden power loss.
  - Atomic manifest writing: `manifest.json` is written via `.tmp` and synchronized with `fsync()`.
  - Complete session marker: a `COMPLETE` marker file ensures partial or interrupted sessions can be reliably identified.
  - Incomplete session discovery: partial or damaged captures remain visible in the gallery with an `[INCOMPLETE]` warning indicator.

- **Live Viewfinder HUD:**
  - Real-time status indicators (`BUFFER ACTIVE`, `SAVING...`, `CAPTURE SAVED`, `ERROR`, `PAUSED`).
  - Simulation mode indicator (`[SIM]`) when running on non-hardware devices.
  - Rolling FPS calculation, frame count, and buffer time span (e.g. `2.0 s | 20 frames | 10.0 FPS`).
  - On-screen save progress indicator.

- **On-Camera Gallery:**
  - Native review interface with full button navigation.
  - Asynchronous background session loading and non-blocking file deletion.
  - Inspect individual frames with their precise millisecond timing relative to shutter release (e.g. `-0.45 s`).
  - Missing or corrupted frames are clearly identified.
  - Ability to delete unwanted sessions directly from the camera.

- **Single-Frame Probe Diagnostic Mode:**
  - Dedicated hardware diagnostic runner accessible via the `Fn` button.
  - Serialized execution pauses live capture during probe to eliminate thread contention.
  - Validates real decodable baseline JPEGs and writes diagnostic reports and sample JPEGs to `<SD Card>/GHOSTBUFFER/probe/`.

- **Preserved PMCADemo Diagnostic Suite:**
  - Pressing the **Menu** button opens the built-in PMCADemo diagnostics suite (Keys, Display, WiFi, Scalar Properties).

---

## Camera Controls

### Viewfinder Mode (Live Buffer)

| Button / Control | Action |
|---|---|
| **Shutter (S2 - Full Press)** | Normal: Freeze buffer & save snapshot / Error state: Retry Save |
| **Shutter (S1 - Half Press)** | Autofocus |
| **Play** | Open GhostBuffer Gallery |
| **Menu** | Open PMCADemo Diagnostic Suite / hardware inspection menu |
| **Fn** | Run Single-Frame Hardware Probe test (serialized with preview) |
| **Trash / Delete (SK2)** | Normal: Exit application / Error state: Discard snapshot & resume buffer |

### Gallery Mode

| Button / Control | Action |
|---|---|
| **D-Pad Left / Right** | Previous / Next frame in session |
| **D-Pad Up / Down** | Jump 5 frames backward / forward in session |
| **Trash / Delete** | Delete current session from SD card (background thread) |
| **Menu / Play** | Return to Viewfinder |

---

## Storage Layout

Captured data is organized under the root directory `GHOSTBUFFER` on the SD card:

```text
<SD Card>/
└── GHOSTBUFFER/
    ├── captures/
    │   └── 20261014_153000_123/
    │       ├── frame_000001.jpg
    │       ├── frame_000002.jpg
    │       ├── ...
    │       ├── manifest.json
    │       └── COMPLETE
    ├── probe/
    │   ├── probe_20261014_153010_123.jpg
    │   └── probe_20261014_153010_123_report.txt
    └── diagnostics/
```

### Manifest Schema (`manifest.json`)

```json
{
  "version": 1,
  "app_version": "1.0.0",
  "camera_model": "ILCE-5000",
  "camera_firmware": "1.10",
  "session_id": "20261014_153000_123",
  "created_at": "2026-10-14T15:30:00.123Z",
  "trigger_time_ms": 50230,
  "buffer_duration_ms": 2000,
  "actual_duration_ms": 1950,
  "target_fps": 10.0,
  "measured_fps": 9.9,
  "frame_width": 640,
  "frame_height": 480,
  "frame_count": 20,
  "frames": [
    {
      "filename": "frame_000001.jpg",
      "sequence": 101,
      "size_bytes": 45120,
      "offset_from_trigger_ms": -1950,
      "read_duration_ms": 12
    }
  ]
}
```

---

## Installation

### Method 1: Using PMCA GUI (Recommended)
1. Download the latest `GhostBuffer-debug-*.apk` from the [Releases](https://github.com/CaskLabs/GhostBuffer/releases) page.
2. Download and launch [Sony-PMCA-RE GUI](https://github.com/ma1co/Sony-PMCA-RE/releases).
3. Connect your camera via USB in "Mass Storage" mode.
4. Select the "Install an app" tab, choose the downloaded APK file, and click "Install".

### Method 2: Command Line
```bash
python pmca-console.py install -f GhostBuffer-debug-*.apk
```

---

## Building from Source

### Requirements
- **JDK 8** (OpenJDK 8 or Eclipse Temurin 8)
- **Android SDK** with Platform 10 (Gingerbread 2.3.3) and Build Tools 25.0.2

### Build Commands
```bash
# Clone repository
git clone https://github.com/CaskLabs/GhostBuffer.git
cd GhostBuffer

# Build debug APK
./gradlew assembleDebug

# Output APK will be at:
# app/build/outputs/apk/GhostBuffer-debug-*.apk
```

### Running Automated Tests
```bash
./gradlew testDebugUnitTest
```

---

## Supported Cameras

Primary development target:
- **Sony α5000 (ILCE-5000)**

Other PMCA-compatible cameras:
- α6000 (ILCE-6000)
- α7 series (ILCE-7, ILCE-7R, ILCE-7S, ILCE-7M2)
- RX100 series (DSC-RX100M2, M3, M4, M5)
- NEX series (NEX-5R, NEX-5T, NEX-6)

---

## Framework & Dependencies

- Uses Sony PMCA Scalar APIs provided by [OpenMemories: Framework](https://github.com/ma1co/OpenMemories-Framework).
- Built with Gradle and Android Gradle Plugin 2.2.3.
- Standard JUnit 4 test suite with 28 automated unit tests.

---

## License

This project is open-source under the [MIT License](LICENSE.txt) compatible with the OpenMemories ecosystem.

---

## V1 Review & Remediations

All findings identified in the V1 review have been remediated and verified through unit tests:

### Remediated Findings

1. **P1.1 – Late save callbacks & paused state re-activation: RESOLVED**
   - Implemented `isForeground` state guard in `CaptureController`. When an asynchronous save completes while the app is paused, the controller transitions to `PAUSED` instead of `REVIEW` / `BUFFERING`.
   - `resumeBuffering()` and `discardSnapshotAndResume()` refuse to start buffering while the activity is paused or in background.
   - Detached pending UI callbacks in `GhostBufferActivity.onPause()`.

2. **P1.2 – Camera & sequence access serialization & shutdown guarantee: RESOLVED**
   - Thread safety: synchronized all methods in `SonyPreviewBackend` to prevent race conditions on `CameraSequence`.
   - `FrameReader.stop()` reliably waits for background reader threads to exit (up to 1500 ms) before clearing references.
   - Probes are serialized through `CaptureController.runProbe()` which safely stops the preview reader before running the probe and resumes preview afterwards.
   - Added guard preventing multiple concurrent probe worker threads.

3. **P1.3 – Surface lifecycle synchronization: RESOLVED**
   - In `GhostBufferActivity`, camera preview display and `CaptureController` are only started once a valid surface is confirmed (`surfaceCreated`).
   - Clean shutdown occurs when `surfaceDestroyed` is invoked.

4. **P1.4 – Explicit simulation mode & real probe validation: RESOLVED**
   - Removed silent fallback to `FakePreviewBackend` when camera open fails on hardware; errors are explicitly reported to the user.
   - Simulation mode is explicitly badged on the HUD with `[SIM]`.
   - `FakePreviewBackend` generates valid, decodable standard baseline JPEGs.
   - `GhostProbeManager` validates decodability using SOI/EOI, `BitmapFactory`, JVM `ImageIO`, and SOF0 frame header inspection via `verifyJpegDecodable()`.

5. **P1.5 – Incomplete session visibility & atomic manifest persistence: RESOLVED**
   - `manifest.json` is written atomically using a `.tmp` file and explicit `fsync()` before renaming.
   - The `COMPLETE` marker is flushed with `fsync()`.
   - `CaptureSession.loadAllSessions()` discovers sessions even if `manifest.json` is missing or corrupted, reconstructing session contents from surviving `frame_*.jpg` files.
   - Incomplete and partially damaged sessions are visibly flagged with `[INCOMPLETE]` badges in the gallery.

6. **P2.6 – Byte budget enforcement: RESOLVED**
   - `FrameRingBuffer.push()` performs upfront budget checks. Oversized frames that exceed `maxByteBudget` are rejected immediately without evicting or corrupting existing buffer frames.

7. **P2.7 – Error recovery paths in UI: RESOLVED**
   - In `ERROR` state, the UI provides two recovery actions:
     - **Shutter button**: Triggers `controller.retrySave()` to retry persisting the frozen snapshot.
     - **Delete button**: Triggers `controller.discardSnapshotAndResume()` to discard the failed snapshot and safely resume live rolling buffering.
   - `FrameReader` tracks frame arrival and notifies `onReaderStale()` if frames stall for more than 2000 ms, clearing stale statistics.

8. **P2.8 – Accurate metadata (App Version & UTC time): RESOLVED**
   - `app_version` in `CaptureManifest` is populated strictly from `BuildConfig.VERSION_NAME`.
   - `camera_firmware` is tracked as a distinct metadata field.
   - `formatIso8601()` and `generateSessionId()` explicitly enforce UTC (`TimeZone.getTimeZone("UTC")`), ensuring accurate time across all local camera timezones.

9. **P2.9 – Gallery missing-frame handling & background I/O: RESOLVED**
   - `GhostGalleryActivity` recycles and clears the current bitmap before decoding the next frame to prevent stale images.
   - Missing or corrupted frames display a clear error placeholder.
   - Session discovery and recursive deletion are offloaded from the UI thread to background worker threads.

10. **P2.10 – Build dependencies & documentation alignment: RESOLVED**
    - Corrected license documentation in `README.md` to MIT.
    - Routed the **Menu** button to `MainActivity` so users can access the PMCADemo hardware diagnostic suite.
    - Updated documentation of camera key bindings, dynamic version naming, and error recovery actions.
