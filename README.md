# GhostBuffer

**GhostBuffer** is an open-source, pre-trigger rolling buffer camera application for Sony PlayMemories Camera Apps (PMCA) compatible mirrorless cameras, specifically developed and tested for the **Sony α5000 (ILCE-5000)**.

---

## Overview

Traditional cameras only begin recording after the shutter is pressed, often missing spontaneous, high-speed, or unpredictable moments (e.g., lightning strikes, wildlife actions, sports milestones).

GhostBuffer solves this by maintaining a continuous rolling ring buffer in memory. The camera captures the live preview stream at native sensor refresh rates, holding the last **2000 ms (2 seconds)** in RAM. When you press the physical shutter button, the pre-trigger buffer is instantly frozen and persisted to your SD card.

---

## Features

- **Rolling Pre-Trigger Ring Buffer:**
  - Continuously retains up to 2 seconds of high-speed preview frames in RAM.
  - Strict memory boundaries (3 MiB heap budget, 25-frame max limit) prevent Dalvik Out-Of-Memory (OOM) crashes.
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
  - Real-time status indicators (`PUFFER AKTIV`, `SPEICHERN...`, `AUFNAHME GESPEICHERT`, `FEHLER`).
  - Rolling FPS calculation, frame count, and buffer time span (e.g. `2.0 s | 20 Bilder | 10.0 FPS`).
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

Primarily developed and tested on:
- **Sony α5000 (ILCE-5000)**

Compatible with most Generation 1 and Generation 2 Sony PMCA cameras running Android 2.3 / 4.1 subsystem:
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
