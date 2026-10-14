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
  - Saves sessions to `<SD Card>/GHOSTBUF/CAPTURES/<8-digit-session-id>/`.
  - Frames are written to uppercase 8.3 temporary files, closed and renamed. Sync is used on supported filesystems; the Sony SD-card FUSE bridge does not implement it.
  - The JSON manifest is written as `MANIFEST.TMP`, closed and renamed to `MANIFEST.JSN`.
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
  - A hardware probe remains accessible via the `Fn` button.
  - Serialized execution pauses live capture during probe to eliminate thread contention.
  - Validates real decodable baseline JPEGs and writes diagnostic reports and sample JPEGs to `<SD Card>/GHOSTBUF/PROBE/`.

- **Camera Menu:**
  - Pressing **Menu** opens a compact Camera / Gallery menu. Legacy demo tools are hidden.

---

## Camera Controls

### Viewfinder Mode (Live Buffer)

| Button / Control | Action |
|---|---|
| **Shutter (S2 - Full Press)** | Normal: Freeze buffer & save snapshot / Error state: Retry Save |
| **Shutter (S1 - Half Press)** | Autofocus |
| **Play** | Open GhostBuffer Gallery |
| **Menu** | Open Camera / Gallery menu |
| **Fn** | Run Single-Frame Hardware Probe test (serialized with preview) |
| **Trash / Delete (SK2)** | Normal: Exit application / Error state: Discard snapshot & resume buffer |

### Gallery Mode

| Button / Control | Action |
|---|---|
| **D-Pad Left / Right** | Previous / Next frame in session |
| **D-Pad Up / Down** | Previous / Next capture session |
| **Trash / Delete** | Delete current session from SD card (background thread) |
| **Menu / Play** | Return to Viewfinder |

---

## Storage Layout

Captured data is organized under the root directory `GHOSTBUF` on the SD card.
Sony’s SD-card FUSE bridge requires uppercase DOS 8.3 names and has no `fsync`.
Every folder, frame, probe and temporary file follows that restriction. Session
IDs contain eight hexadecimal digits derived from the timestamp; collisions
advance the ID while the precise capture time is preserved in `MANIFEST.JSN`.
The app checks the Android external-storage path and the Sony SD-card aliases
`/storage/sdcard0`, `/mnt/sdcard` and `/sdcard` using an actual file write. It only
uses existing storage roots and creates directories one component at a time.
If the card is missing, locked or full, saving reports a storage error and the
buffer remains available for retry. Capture and probe timestamps use the camera
clock, since Sony's Android system clock may still show 1970.

```text
<SD Card>/
└── GHOSTBUF/
    ├── CAPTURES/
    │   └── 6ACF9FF8/
    │       ├── F0000001.JPG
    │       ├── F0000002.JPG
    │       ├── ...
    │       ├── MANIFEST.JSN
    │       └── COMPLETE
    ├── PROBE/
    │   ├── 6ACFA002.JPG
    │   └── 6ACFA002.TXT
    └── DIAG/
```

### Manifest Schema (`MANIFEST.JSN`)

```json
{
  "version": 1,
  "app_version": "1.0.0",
  "camera_model": "ILCE-5000",
  "camera_firmware": "1.10",
  "session_id": "6ACF9FF8",
  "created_at": "2026-10-14T15:30:00.123Z",
  "trigger_time_ms": 1791991800123,
  "buffer_duration_ms": 2000,
  "actual_duration_ms": 1950,
  "target_fps": 10.0,
  "measured_fps": 9.9,
  "frame_width": 640,
  "frame_height": 480,
  "frame_count": 20,
  "frames": [
    {
      "filename": "F0000001.JPG",
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
   - `MANIFEST.JSN` is written atomically using `MANIFEST.TMP` before renaming, with sync where supported.
   - The `COMPLETE` marker is written after all frames and the manifest are closed; sync is skipped on Sony SD storage.
   - `CaptureSession.loadAllSessions()` discovers sessions even if `MANIFEST.JSN` is missing or corrupted, reconstructing session contents from surviving `F*.JPG` files (legacy `frame_*.jpg` and `manifest.json` formats remain readable within the captures directory).
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
   - `GhostGalleryActivity` detaches images before recycling and decodes on one background worker with display-sized memory bounds. Stale callbacks are discarded after navigation or pause.
   - Missing or corrupted frames display a clear error placeholder.
   - Session discovery and recursive deletion are offloaded from the UI thread to background worker threads.

10. **P2.10 – Build dependencies & documentation alignment: RESOLVED**
    - Corrected license documentation in `README.md` to MIT.
    - The **Menu** button opens `MainActivity`, now limited to Camera and Gallery.
    - Updated documentation of camera key bindings, dynamic version naming, and error recovery actions.

### Sony SD-card compatibility

The 8.3-name and unsupported-sync constraints are documented in [Recipe Lab’s SD-storage fix](https://github.com/voxivoid/recipe-lab-sony-pmca/commit/e5e7949). A regression test rejects every incompatible file-write path, including temporary files, and exercises captures, collisions, probes and incomplete-session recovery.

Launcher icon source and generation prompt: [assets/icon/README.md](assets/icon/README.md).
