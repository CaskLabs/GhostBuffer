# GhostBuffer

**GhostBuffer** is an open-source, pre-trigger rolling buffer camera application for Sony PlayMemories Camera Apps (PMCA) compatible mirrorless cameras, specifically targeting the **Sony α5000 (ILCE-5000)**.

> **V1 review (14 October 2026):** The debug build and 21 host unit tests pass, but release-blocking lifecycle and data-integrity issues remain. Camera compatibility and performance have not been verified in this review. See [V1 Review](#v1-review) for findings and validation limits.

---

## Overview

Traditional cameras only begin recording after the shutter is pressed, often missing spontaneous, high-speed, or unpredictable moments (e.g., lightning strikes, wildlife actions, sports milestones).

GhostBuffer solves this by maintaining a continuous rolling ring buffer in memory. The application polls the live preview stream at a target rate of 10 FPS, retaining up to **2000 ms (2 seconds)** in RAM. Actual distinct-frame rate and latency require camera measurements. When you press the physical shutter button, the pre-trigger buffer is instantly frozen and persisted to your SD card.

---

## Features

- **Rolling Pre-Trigger Ring Buffer:**
  - Continuously retains up to 2 seconds of high-speed preview frames in RAM.
  - The ring targets a 3 MiB JPEG payload budget and a 25-frame limit. This is not a total heap limit; snapshot copies, native buffers and gallery bitmaps add memory usage (see review).
  - Time-window head eviction ensures oldest frames roll over seamlessly.

- **Zero Shutter Lag & Latching:**
  - Direct hardware shutter hook (`ISV_KEY_S2`).
  - Hardware repeat suppression prevents accidental multi-triggers when holding down the button.
  - Thread-safe snapshot freezing (`freezeSnapshot()`) decouples capture from background disk I/O.

- **Failsafe Storage Architecture:**
  - Saves sessions to `<SD Card>/GHOSTBUFFER/captures/<YYYYMMDD_HHmmss_SSS>/`.
  - Atomic frame writing: images are written as temporary files (`.tmp`) and synced before renaming to prevent corrupt files upon sudden power loss.
  - Complete session marker: a `COMPLETE` marker file ensures partial or interrupted sessions can be reliably identified.
  - Detailed `manifest.json` for every session containing per-frame timing offsets relative to the shutter trigger, file sizes, and measured FPS.

- **Live Viewfinder HUD:**
  - Real-time status indicators (`BUFFER ACTIVE`, `SAVING...`, `CAPTURE SAVED`, `ERROR`).
  - Rolling FPS calculation, frame count, and buffer time span (e.g. `2.0 s | 20 frames | 10.0 FPS`).
  - On-screen save progress indicator.

- **On-Camera Gallery:**
  - Native review interface with full button and dial navigation.
  - Inspect individual frames with their precise millisecond timing relative to shutter release (e.g. `-0.45 s`).
  - Ability to delete unwanted sessions directly from the camera.

- **Single-Frame Probe Diagnostic Mode:**
  - Dedicated hardware diagnostic runner accessible via the `Fn` button.
  - Directly tests Sony `CameraSequence` JPEG stream decoding and writes diagnostic reports and sample JPEGs to `<SD Card>/GHOSTBUFFER/probe/`.

- **Preserved PMCADemo Diagnostic Suite:**
  - Retains all hardware inspection activities (Keys, Display, WiFi, Properties, LEDs) accessible via the main menu.

---

## Camera Controls

### Viewfinder Mode (Live Buffer)

| Button / Control | Action |
|---|---|
| **Shutter (S2 - Full Press)** | Freeze buffer and save snapshot sequence to SD card |
| **Shutter (S1 - Half Press)** | Autofocus |
| **Play** | Open GhostBuffer Gallery |
| **Menu** | Open GhostBuffer Gallery |
| **Fn** | Run Single-Frame Hardware Probe test |
| **Trash / Delete (SK2)** | Exit application |

### Gallery Mode

| Button / Control | Action |
|---|---|
| **D-Pad Left / Right** | Previous / Next frame in session |
| **D-Pad Up / Down** | Previous / Next capture session |
| **Trash / Delete** | Delete current session from SD card |
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
1. Download the latest `GhostBuffer-debug-1.0.0.apk` from the [Releases](https://github.com/CaskLabs/GhostBuffer/releases) page.
2. Download and launch [Sony-PMCA-RE GUI](https://github.com/ma1co/Sony-PMCA-RE/releases).
3. Connect your camera via USB in "Mass Storage" mode.
4. Select the "Install an app" tab, choose the downloaded APK file, and click "Install".

### Method 2: Command Line
```bash
python pmca-console.py install -f GhostBuffer-debug-1.0.0.apk
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

# Build APK
./gradlew assembleDebug

# Output APK will be at:
# app/build/outputs/apk/GhostBuffer-debug-1.0.0.apk
```

### Running Automated Tests
```bash
./gradlew testDebugUnitTest
```

---

## Supported Cameras

Primary development target (hardware validation remains open in this review):
- **Sony α5000 (ILCE-5000)**

Other potential PMCA targets, without compatibility verification in this review:
- α6000 (ILCE-6000)
- α7 series (ILCE-7, ILCE-7R, ILCE-7S, ILCE-7M2)
- RX100 series (DSC-RX100M2, M3, M4, M5)
- NEX series (NEX-5R, NEX-5T, NEX-6)

---

## Framework & Dependencies

- Uses Sony PMCA Scalar APIs provided by [OpenMemories: Framework](https://github.com/ma1co/OpenMemories-Framework).
- Built with Gradle and Android Gradle Plugin 2.2.3.
- Standard JUnit 4 test suite with mockable preview backends.

---

## License

This project is open-source under the MIT / Apache 2.0 license compatible with the OpenMemories ecosystem.


---

## V1 Review

**Date:** 14 October 2026. Reviewed revision: `ed41c7b`, containing the V1 implementation from `fd56731`. Scope: capture pipeline, UI, gallery, probe, storage management, build configuration and existing tests. This review documents required work; it did not fix implementation bugs.

**Assessment:** V1 has a useful separation between backend, ring buffer, controller and writer. Filtering the time window at the trigger, independent snapshot copies, shutter latching and releasing all returned `DeviceMemory` objects in `finally` provide a good foundation. However, the following issues and hardware checks remain open before a reliable A5000 release. The product descriptions above do not constitute hardware acceptance.

### Validation and limitations

- Using the locally available JDK 8, Android SDK 10 and Build Tools 25.0.2, `JAVA_HOME=/home/agent/tools/jdk8 PATH=/home/agent/tools/jdk8/bin:$PATH ./gradlew testDebugUnitTest assembleDebug --offline` completed successfully. **21 tests, 0 failures, 0 skipped.** The offline build validates the existing cache, not a clean first build on another machine.
- Additional temporary JVM checks against the compiled classes confirmed: a successful probe with an undecodable JPEG; acceptance of a 240-byte frame with a 100-byte budget; an invisible session without a manifest; `BUFFERING` with `frameReader == null` after `pause()` and `resumeBuffering()`, including after a simulated late save callback; and an incorrect UTC suffix in another time zone. These check programs were not added to the repository.
- No Sony camera was connected. Installation, actual Sony API semantics, autofocus, distinct rather than repeated frames, SD behavior, memory consumption, buttons and long-term stability were not tested on hardware. Host tests with the fake backend do not replace those checks.

### Findings by priority

**P1 = fix before a reliable V1 release; P2 = relevant functional or quality gap.** A “code finding” describes behavior inferred from the source; its hardware consequences have not been measured.

1. **P1 – Late save callbacks can make a paused controller appear active again.**

   Sources: [CaptureController.java](app/src/main/java/com/casklabs/ghostbuffer/capture/CaptureController.java), `pause`, `start`, `onSaveComplete`, `resumeBuffering`; [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), `onSaveComplete`.

   Sequence: The gallery can be opened while saving. `pause()` removes the reader and closes the backend but preserves `SAVING`. If the writer completes afterward, it sets `REVIEW` without a lifecycle check; the delayed UI callback then calls `resumeBuffering()`. This method sets `BUFFERING` even without a reader. Returning quickly during `SAVING` also causes `start()` to skip initialization. The partial sequence with a late callback was confirmed on the JVM. **Impact:** The UI can report capture readiness while no frames are being read. **Action:** Check foreground state and run generation, detach stale callbacks, remove queued UI work on pause and fully reinitialize the camera/reader before setting `BUFFERING`. **Acceptance:** Open the gallery during a slow save, stay there and return repeatedly; no background buffering and reliably fresh frames after returning.

2. **P1 – Camera access is not serialized, and stopping the reader does not guarantee termination.**

   Sources: [FrameReader.java](app/src/main/java/com/casklabs/ghostbuffer/preview/FrameReader.java), `stop`; [SonyPreviewBackend.java](app/src/main/java/com/casklabs/ghostbuffer/preview/SonyPreviewBackend.java), `readNextFrame`, `close`; [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), `runProbe`, `onPause`.

   Code finding: Fn starts another thread using the same backend and direct ByteBuffer in parallel with the reader. Repeated Fn presses allow more threads. `stop()` waits at most 500 ms and then discards the thread reference; the sequence and camera can subsequently be closed while a native read is still running. Restarting also shares the `running` flag with the old thread. **Impact:** Potential corrupt frames, concurrent native access and release of resources still in use. **Action:** Use one owner thread for camera, sequence and probe; confirm termination before releasing resources or starting new readers. **Acceptance:** Simulate a blocking backend call and test pause, restart and repeated Fn presses; measure hardware cancellation behavior separately.

3. **P1 – The camera sequence starts before a valid live-view surface is confirmed.**

   Source: [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), `onResume`, `surfaceCreated`, `surfaceDestroyed`.

   Code finding: `onResume()` starts the controller immediately after opening the camera. The preview display and normal preview are configured only in `surfaceCreated()`; surface loss stops only the normal preview. **Impact:** Initialization depends on callback order, while the reader can continue after losing its surface. **Action:** Gate initialization on foreground state, a valid surface and a started preview; include surface loss in orderly shutdown and release resources after failed initialization. **Acceptance:** Repeatedly test startup, surface recreation and pause/resume on the A5000.

4. **P1 – Camera failures can silently switch to synthetic captures.**

   Sources: [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), backend selection and `onResume`; [FakePreviewBackend.java](app/src/main/java/com/casklabs/ghostbuffer/preview/FakePreviewBackend.java); [GhostProbeManager.java](app/src/main/java/com/casklabs/ghostbuffer/probe/GhostProbeManager.java).

   A missing sequence class or failed `CameraEx.open()` automatically selects the fake backend. It produces JPEG markers and dummy bytes but no decodable image. Nevertheless, the probe checks only the SOI prefix and reports success; the writer can store such data as a complete session. JVM evidence: `Probe success=true`, but `ImageIO.read` returns no image or fails. **Action:** Make simulation explicitly selectable and visible; report camera failures as errors. The hardware probe must demonstrate real, decodable, changing images. **Acceptance:** A camera-open failure must not produce an apparently real capture; fully decode the exported probe.

5. **P1 – Interrupted sessions disappear from the gallery, and persistence is only partially protected.**

   Sources: [CaptureWriter.java](app/src/main/java/com/casklabs/ghostbuffer/capture/CaptureWriter.java), `writeSnapshot`; [CaptureSession.java](app/src/main/java/com/casklabs/ghostbuffer/capture/CaptureSession.java), `loadAllSessions`; [GhostGalleryActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostGalleryActivity.java).

   The manifest is created only after all JPEGs. An early write failure leaves neither manifest nor `COMPLETE`. The loader includes only sessions with a readable manifest; such an interrupted session was omitted in the JVM check. Even loaded incomplete sessions are not labeled incomplete in the gallery. JPEGs are synced and renamed, but the manifest and marker are not synced in the same way; a marker alone does not verify that frames exist and are intact. **Action:** Show incomplete directories and their status in the gallery, write the manifest atomically, sync data before completion where supported and validate it when loading. **Acceptance:** Simulate failure after the first JPEG, a damaged manifest and a missing frame; no false success or hidden leftover data. Power-loss behavior remains an SD hardware test.

6. **P2 – The byte budget limits neither every insert nor total memory use.**

   Sources: [FrameRingBuffer.java](app/src/main/java/com/casklabs/ghostbuffer/buffer/FrameRingBuffer.java), `push`, `freezeSnapshot`; [FrameSlot.java](app/src/main/java/com/casklabs/ghostbuffer/buffer/FrameSlot.java).

   A frame larger than the budget is still inserted after evicting all previous frames: JVM evidence showed 240 bytes with a 100-byte budget. The current Sony source limits individual frames to 256 KiB, so this particular overflow is not reached there with the default budget. Separately, `freezeSnapshot()` temporarily doubles the JPEG arrays; the original ring remains in memory during saving. The direct buffer, bitmaps and native camera resources add further usage. **Action:** Reject oversized frames and invalid lengths, account for actual array allocation and budget snapshot/ring ownership together. **Acceptance:** Test boundary cases and measure peak memory at the trigger on the camera; do not present 3 MiB as an OOM guarantee.

7. **P2 – Error states lack a complete recovery path through the UI.**

   Sources: [CaptureController.java](app/src/main/java/com/casklabs/ghostbuffer/capture/CaptureController.java), `onReaderError`, `retrySave`, `resumeBuffering`; [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), button mapping and statistics callbacks.

   Code finding: After read errors the reader stays active, but the controller remains in `ERROR`. Persistently empty frames produce no error/stale status, leaving the last statistics visible. `retrySave()` exists but is never called by the UI. Opening the gallery and returning can restart capture without explicitly saving or discarding the retained failed snapshot. **Action:** Define handling for reader errors and frame outages; offer retry and deliberate discard through camera buttons. **Acceptance:** Simulate empty frames, read failures and a full card; status must reflect actual operation and every error must have an accessible recovery path.

8. **P2 – The manifest contains the wrong app version and potentially incorrect UTC time.**

   Sources: [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), `onCreate`; [GhostStorage.java](app/src/main/java/com/casklabs/ghostbuffer/storage/GhostStorage.java), `formatIso8601`; [app/build.gradle](app/build.gradle).

   `setAppVersion(getDeviceInfo().getFirmwareVersion())` writes camera firmware into `app_version`. The ISO formatter uses the local time zone but appends a literal `Z`. JVM evidence in GMT+02:00: epoch 0 becomes `1970-01-01T02:00:00.000Z`. **Action:** Take the app version from `BuildConfig.VERSION_NAME`, record firmware separately and explicitly format time in UTC. **Acceptance:** Compare metadata with the APK version and test at least two time zones.

9. **P2 – The gallery can show the previous image when a frame is missing.**

   Source: [GhostGalleryActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostGalleryActivity.java), `displayCurrentFrame`, `deleteRecursive`.

   Code finding: If the selected file does not exist, the previous image is not cleared, while its caption and time offset change to the new frame. Session scanning, decoding and recursive deletion also run on the UI thread; deletion failures are ignored. **Action:** Clear the image before switching, visibly mark missing/damaged frames, move SD work off the UI thread and report deletion results. **Acceptance:** Remove a referenced file, switch frames and simulate a slow or unwritable card.

10. **P2 – Build reproducibility and documented controls are not fully established.**

    Sources: [app/build.gradle](app/build.gradle), [AndroidManifest.xml](app/src/main/AndroidManifest.xml), [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), [GhostGalleryActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostGalleryActivity.java).

    Framework and stubs use moving `-SNAPSHOT` versions. The APK version is derived from Git; the review produced `GhostBuffer-debug-1.0.0-1-ged41c7b.apk`, so the filename above is not a fixed build output. The diagnostic menu is declared but cannot be reached from the launcher or GhostBuffer buttons. The gallery implements directional buttons, with no custom dial navigation. `LICENSE.txt` contains MIT; it does not establish the README claim “MIT / Apache 2.0.” **Action:** Pin verified dependencies, demonstrate a clean first build, explain versions/filenames and align controls and license descriptions with the implementation.

### Recommended acceptance before release

First address lifecycle, serialization, simulation and recovery of incomplete sessions (P1). Then cover the P2 cases with targeted tests. Existing tests primarily check successful flows with synthetic data; they do not cover blocking Sony calls, actual image decoding, UI lifecycle or early write failures.

Next, document hardware acceptance from [GHOSTBUFFER_PLAN.md](GHOSTBUFFER_PLAN.md): model/firmware and installation; at least ten decodable, changing probe JPEGs across two starts; measured distinct frames/s and latency; 50 saves including a held shutter; missing/full/removed SD card; repeated pause and gallery navigation; and at least 30 minutes of operation with memory tracking. Manifest time offsets refer to the end of the app read, not verified sensor exposure times. VGA preview frames, actual available buffer coverage and the capture gap during saving/gallery use must remain documented V1 limitations.
