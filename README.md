# GhostBuffer

**GhostBuffer** is an open-source, pre-trigger rolling buffer camera application for Sony PlayMemories Camera Apps (PMCA) compatible cameras. It has been tested on the **Sony α5000 (ILCE-5000)**; other models have not yet been tested with GhostBuffer.

> **Status (October 2026):** GhostBuffer includes rolling capture, on-camera review, exposure controls and Sony SD-card storage support. The app targets Android 2.3.3 (API 10) and has been tested on the Sony α5000. Hardware behavior on other PMCA-compatible cameras remains unverified.

---

## Overview

Traditional cameras only begin recording after the shutter is pressed, often missing spontaneous, high-speed, or unpredictable moments (e.g., lightning strikes, wildlife actions, sports milestones).

GhostBuffer solves this by maintaining a continuous rolling ring buffer in memory. The application polls the live preview stream at a target rate of 10 FPS, retaining up to **2000 ms (2 seconds)** in RAM. When you press the physical shutter button, the pre-trigger buffer is instantly frozen and persisted to your SD card.

The native preview is requested at **640 × 480 pixels**. Captures contain individual JPEG preview frames, rather than full-resolution still photos or a video file. Actual frame rate and retained duration depend on the camera and JPEG sizes; the viewfinder shows the measured values.

### Quick Start

1. Install the APK using the [installation instructions](#installation), then launch **GhostBuffer** on the camera.
2. Wait for **BUFFER ACTIVE** and let the buffer fill for about two seconds.
3. Half-press the shutter to autofocus, then fully press it to save the preceding frames.
4. Wait for saving to finish. Buffering resumes automatically after the saved-capture message (about 2.5 seconds).
5. Press **Play** to review the capture. Use **Left / Right** for frames and **Up / Down** for capture sessions.

---

## Features

- **Rolling Pre-Trigger Ring Buffer:**
  - Continuously retains up to 2 seconds of high-speed preview frames in RAM.
  - Strict byte-budget enforcement: rejects oversized incoming frames without corrupting the buffer, maintaining a 3 MiB JPEG payload budget and a 25-frame limit.
  - Time-window head eviction ensures oldest frames roll over seamlessly.

- **Pre-Trigger Capture & Shutter Latching:**
  - Direct hardware shutter hook (`ISV_KEY_S2`).
  - Hardware repeat suppression prevents accidental multi-triggers when holding down the button.
  - Thread-safe snapshot freezing (`freezeSnapshot()`) decouples capture from background disk I/O.

- **Failsafe Storage Architecture:**
  - Saves sessions to `<SD Card>/GHOSTBUF/CAPTURES/<8-digit-session-id>/`.
  - Frames are written to uppercase 8.3 temporary files, closed and renamed. Sync is used on supported filesystems; the Sony SD-card FUSE bridge does not implement it.
  - The JSON manifest is written as `MANIFEST.TMP`, closed and renamed to `MANIFEST.JSN`.
  - Complete session marker: a `COMPLETE` marker file ensures partial or interrupted sessions can be reliably identified.
  - Incomplete session discovery: partial or damaged captures remain visible in the gallery with an `INCOMPLETE` warning indicator.

- **Live Viewfinder HUD:**
  - Real-time status indicators (`BUFFER ACTIVE`, `SAVING…`, `SAVED`, `ERROR`, `PAUSED`).
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
  - Hold `Fn` for a hardware probe; a short press opens exposure controls.
  - Serialized execution pauses live capture during probe to eliminate thread contention.
  - Validates real decodable baseline JPEGs and writes diagnostic reports and sample JPEGs to `<SD Card>/GHOSTBUF/PROBE/`.

- **Camera Menu:**
  - Pressing **Menu** opens a compact Camera / Gallery / Exposure menu. Legacy demo tools are hidden.
  - Both the camera menu and exposure panel scroll to keep the selected item visible on compact camera displays.

---

## Camera Controls

### Viewfinder Mode (Live Buffer)

| Button / Control | Action |
|---|---|
| **Shutter (S2 - Full Press)** | Normal: Freeze buffer & save snapshot / Error state: Retry Save |
| **Shutter (S1 - Half Press)** | Autofocus |
| **Play** | Open GhostBuffer Gallery |
| **Menu** | Open Camera / Gallery / Exposure menu |
| **Fn / Center** | Open exposure controls; hold Fn for at least 0.8 seconds, then release for the hardware probe |
| **Trash / Delete (SK2)** | Normal: Exit application / Error state: Discard snapshot & resume buffer |

### Camera Menu

Use **Up / Down** to select **Camera**, **Gallery** or **Exposure**, then press
**Center** to open it. **Menu** returns to the camera, and **Play** opens the
gallery directly. Menu entries can also be tapped on devices with touch input.
Opening the menu or gallery pauses live buffering; returning to the camera
starts a fresh buffer.

### Exposure Controls

Open the exposure panel with **Fn / Center** in the viewfinder or select
**Exposure** from the camera menu. It opens over the live view without opening a second camera.
Use **Up / Down** to select a row, **Left / Right** to change its value and
**Center** to restore automatic control (or neutral **0 EV**). **Fn, Menu,
Play or Delete** closes the panel. Touch controls provide the same actions.

| Setting | Manual control | Center action |
|---|---|---|
| Exposure mode | Supported P / A / S / M modes | P automation and Auto ISO where available |
| Shutter speed | Native camera steps; selects S or M as needed | Auto shutter while retaining the aperture mode |
| Aperture | Native lens steps; selects A or M as needed | Auto aperture while retaining the shutter mode |
| ISO | Values reported by the camera, including Auto when supported | Auto ISO |
| Exposure compensation | Camera-reported range and EV step | Neutral 0 EV |
| White balance | Camera-reported presets | Auto white balance |

Camera settings are inherited when opening the controls; opening the panel does
not force manual exposure. Lenses without electronic aperture control show
**Lens ring**, and their aperture must be adjusted physically. Unavailable
controls are dimmed. EV compensation in M is offered only with Auto ISO and
camera-reported support. Each exposure change starts a fresh frame buffer so a
capture does not combine frames from different settings. Setting writes are
serialized with native preview reads and rejected while paused or saving.

The native controls use the [OpenMemories CameraEx API](https://github.com/ma1co/OpenMemories-Framework/blob/master/stubs/src/main/jasmin/com/sony/scalar/hardware/CameraEx%24ParametersModifier.j);
shutter/aperture step methods and Auto ISO semantics are demonstrated by
[BetterManual](https://github.com/obs1dium/BetterManual).

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

Use a debug APK from the [Releases](https://github.com/CaskLabs/GhostBuffer/releases)
page, when available, or [build one from source](#building-from-source).
The repository also contains [GhostBuffer-sizing-debug.apk](GhostBuffer-sizing-debug.apk),
a debug build for checking menu sizing on camera displays.

### Method 1: Using PMCA GUI

1. Obtain a GhostBuffer APK using one of the options above.
2. Download and launch [Sony-PMCA-RE GUI](https://github.com/ma1co/Sony-PMCA-RE/releases).
3. Connect your camera via USB in "Mass Storage" mode.
4. Select the "Install an app" tab, choose the downloaded APK file, and click "Install".

### Method 2: Command Line

Run this from a Sony-PMCA-RE checkout with its dependencies installed, replacing
the APK path with the file you downloaded or built:

```bash
python pmca-console.py install -f /path/to/GhostBuffer-debug.apk
```

---

## Building from Source

### Requirements
- **JDK 8** (OpenJDK 8 or Eclipse Temurin 8)
- **Android SDK** with Platform 10 (Gingerbread 2.3.3) and Build Tools 25.0.2
- Network access for Gradle and Maven dependencies, including the OpenMemories snapshots from JitPack

Use JDK 8 for this legacy build (Gradle 3.3 / Android Gradle Plugin 2.2.3).
Set `JAVA_HOME` to that JDK and configure the Android SDK in an untracked
`local.properties` file at the repository root:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

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

The local JVM suite covers ring-buffer limits, capture state and retry handling,
manifest serialization, capture writing and recovery, Sony 8.3 storage paths,
frame-reader lifecycle, JPEG probes, exposure controls and gallery image sizing.
It uses simulated preview frames; native Sony APIs, button handling and display
layout also require checks on a physical camera. Test reports are written to
`app/build/reports/tests/debug/index.html`.

---

## Camera Compatibility

Tested with GhostBuffer:
- **Sony α5000 (ILCE-5000)**

Other PMCA-compatible models have not yet been tested with GhostBuffer. Compatibility and available controls may vary by model, firmware and lens. Examples of other PMCA-compatible cameras include:
- α6000 (ILCE-6000)
- α7 series (ILCE-7, ILCE-7R, ILCE-7S, ILCE-7M2)
- RX100 series (DSC-RX100M2, M3, M4, M5)
- NEX series (NEX-5R, NEX-5T, NEX-6)

---

## Framework & Dependencies

- Uses Sony PMCA Scalar APIs provided by [OpenMemories: Framework](https://github.com/ma1co/OpenMemories-Framework).
- Built with Gradle 3.3 and Android Gradle Plugin 2.2.3.
- Local JVM tests use JUnit 4.12 and `org.json`.

---

### Sony SD-card compatibility

The 8.3-name and unsupported-sync constraints are documented in [Recipe Lab’s SD-storage fix](https://github.com/voxivoid/recipe-lab-sony-pmca/commit/e5e7949). A regression test rejects every incompatible file-write path, including temporary files, and exercises captures, collisions, probes and incomplete-session recovery.

Launcher icon source and generation prompt: [assets/icon/README.md](assets/icon/README.md).

---

## License

This project is open-source under the [MIT License](LICENSE.txt) compatible with the OpenMemories ecosystem.
