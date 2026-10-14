# GhostBuffer

**GhostBuffer** is an open-source, pre-trigger rolling buffer camera application for Sony PlayMemories Camera Apps (PMCA) compatible cameras. It has been tested on the **Sony α5000 (ILCE-5000)**; other models have not yet been tested with GhostBuffer.

> **Status (October 2026):** All 10 architectural and lifecycle remediations from the V1 review have been fully implemented and verified with 134 automated unit tests. The build produces a clean, runnable APK targeting Android 2.3.3 (Gingerbread API 10). See [V1 Review & Remediations](#v1-review--remediations) for detailed resolution of each finding.

---

## Overview

Traditional cameras only begin recording after the shutter is pressed, often missing spontaneous, high-speed, or unpredictable moments (e.g., lightning strikes, wildlife actions, sports milestones).

GhostBuffer solves this by maintaining a continuous rolling ring buffer in memory. The application polls the live preview stream at up to 10 FPS, retaining up to **2000 ms (2 seconds)** in RAM. Higher-resolution settings use lower polling rates to reduce memory and processing pressure. When you press the physical shutter button, the pre-trigger buffer is instantly frozen and persisted to your SD card.

---

## Features

- **Rolling Pre-Trigger Ring Buffer:**
  - Continuously retains up to 2 seconds of high-speed preview frames in RAM.
  - Strict byte-budget enforcement: rejects oversized incoming frames without corrupting the buffer, with a 3–8 MiB JPEG payload budget based on the app's maximum heap size and a 25-frame limit. The budget reserves space for the separate frozen snapshot and the UI; it can shorten the effective buffer duration for large JPEGs.
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
  - Hold `Fn` for a hardware probe; a short press opens exposure controls.
  - Serialized execution pauses live capture during probe to eliminate thread contention.
  - Validates real decodable baseline JPEGs and writes diagnostic reports and sample JPEGs to `<SD Card>/GHOSTBUF/PROBE/`.

- **Camera Menu:**
  - Pressing **Menu** opens a compact Camera / Gallery / Exposure menu. Legacy demo tools are hidden.

---

## Camera Controls

### Viewfinder Mode (Live Buffer)

| Button / Control | Action |
|---|---|
| **Shutter (S2 - Full Press)** | Normal: Freeze buffer & save snapshot / Error state: Retry Save |
| **Shutter (S1 - Half Press)** | Autofocus |
| **Play** | Open GhostBuffer Gallery |
| **Menu** | Open Camera / Gallery / Settings menu |
| **Fn / Center** | Open camera settings; hold Fn for 0.8 seconds for the hardware probe |
| **Trash / Delete (SK2)** | Normal: Exit application / Error state: Discard snapshot & resume buffer |

### Camera Settings

The settings panel opens over the live view without opening a second camera.
Use **Up / Down** to select a row, **Left / Right** to change its value and
**Center** to restore automatic control (or neutral **0 EV**). **Fn, Menu,
Play or Delete** closes the panel. Touch controls provide the same actions.

| Setting | Manual control | Center action |
|---|---|---|
| Resolution | 640×480 / 1280×720 / 1920×1080 | Default 640×480 |
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

#### Image Resolution

Select **Resolution** using Up/Down and change the requested size using Left/Right
or the −/+ buttons. The choice is remembered between app starts. Center restores
640×480. Polling targets are 10 FPS at 640×480, 5 FPS at 1280×720 and 3 FPS at
1920×1080; actual rates depend on the camera.

The **Active** line shows the dimensions read from a real JPEG, not just the
requested setting. Larger sizes are experimental and have not yet been verified
on the α5000. The Sony live-preview API exposes size options, but its
[available documentation](https://github.com/ma1co/OpenMemories-Framework/issues/35)
only reports a tested 640×480 configuration on the α6000.

If a camera rejects a size, ignores it or provides no usable frames, GhostBuffer
tries the next smaller size. A fallback is shown explicitly while the requested
choice remains selected. Returning from the gallery reuses a previously verified
fallback in the same app process. Resolution checks run in the background and can
be cancelled when the camera view closes.

Each resolution change clears the old live buffer. Saved manifests record the
actual JPEG dimensions and measured frame rate. Native JPEG read limits increase
from 256 KiB at VGA to 1 MiB at 720p and 2 MiB at 1080p; the overall RAM budget
still applies. The app saves the delivered JPEG bytes without resizing them.

### Internal Video Encoder Test

Open **Fn → Settings → Video encoder test** and press **Center** (or tap the row).
Revision 5 starts a six-second **Baseline** recording without live file reads.
After it finishes, press **Right** (or tap **RIGHT Live**) for a separate
six-second recording with live SD reads. **Center/Left** repeats Baseline.
Each run saves its own report; compare `BASELINE_NO_LIVE_READ` with `LIVE_SD_READ`.
The test uses its own screen and camera connection after the JPEG preview has
paused. It checks the Sony recorder class, StreamBuffer API, Scalar API version,
current settings and firmware-reported recording profiles. The test uses a
reported **FHD_1080** profile or an **AVCHD HD_1080** profile with FX/FH quality,
16:9 aspect and 24p/25p/50i/60i rate. Scalar API 3 on the tested α5000 uses the
same **HD_1080** size name for AVCHD and MPEG4. Sony documents these AVCHD
profiles as **1920×1080**, whereas MPEG4 HD_1080 HQ corresponds to **1440×1080**
and is excluded. See Sony's
[ILCE-5000 movie recording settings](https://helpguide.sony.net/gbmig/44879441/v1/en/contents/TP0000301880.html).
Progressive 24p/25p profiles are preferred when available. Revision 3 gives each
test a fresh preview surface, stops even a partially started preview and detaches
the display target before releasing the camera. This addresses reuse of the
previous preview target. Both supplied revision 3 reports successfully started
the preview; broader reliability testing is still pending.

On the tested α5000 (firmware 1.00, Scalar API 3), revision 2 successfully prepared,
started and stopped AVCHD HD_1080 / 25p / FH and created a 3,342,336-byte MTS file.
A subsequent attempt failed at `startPreview`, before recorder configuration.
These reports establish native SD recording access, while recorded dimensions
were not checked by revision 2.

Revision 3 report `6AD00135.TXT` successfully started the fresh movie preview,
prepared, started and stopped the same AVCHD profile, and created `00008.MTS`
(4,227,072 bytes). The recorded clip's H.264 SPS declares **1920×1080**, producing
**FHD_CLIP_HEADER**. The follow-up report `6AD00285.TXT` also completed preview,
prepare, start and stop successfully, creating `00010.MTS` (4,128,768 bytes) with
the same **1920×1080** SPS dimensions and **FHD_CLIP_HEADER** result. The preview
failure seen with revision 2 did not recur in either supplied revision 3 report.
These two runs confirm Full-HD dimensions in recorded video metadata; decoded
frames, measured video frame rate and longer-term reliability have not been
verified. The JPEG preview path remains separate. The tested
StreamBuffer class was absent on that firmware, so direct RAM stream capture
has not been established.

Revision 4 report `6AD00534.TXT` returned from `start()`, but `stop()` and its
cleanup retry both threw `IllegalStateException`. A 1,179,648-byte MTS file was
visible after cleanup; no live file or bytes were observed before stop. Because
recording did not complete normally, this is **inconclusive** for live access.
It does not prove that a healthy recording's growing file is inaccessible.

Revision 5 separates baseline and live-read modes and disables the V4
stream-write listener in both, isolating live file reads as their difference.
Both use `OnRecordListener` to log the raw `onStarted(boolean, recorder)` value
and callback timing, `onStopped(recorder)`, and requested start/stop times. The
boolean's undocumented meaning is not assumed. An unsolicited stop is treated
as a recording error; an already confirmed native stop is not repeated. If a
planned stop throws, the same stop call is not retried in cleanup, while native
resource release is still attempted. Reports also include bounded exception
stack details and activity/cancellation state. See the
[record-state listener definition](https://github.com/ma1co/OpenMemories-Framework/blob/master/stubs/src/main/jasmin/com/sony/scalar/media/MediaRecorder%24OnRecordListener.j).

If the profile is retained after configuration, the test attempts to prepare,
start and stop the native recorder using the SDK's default recording media.
Revisions 4–5 record for six seconds; revisions 1–3 used
two seconds. This can create a normal movie on the SD card, including audio
if enabled by the native recorder. The diagnostic build declares microphone
permission for native movie recording. Original recorder parameters are restored
and camera resources released before returning to the live view.

The result and each attempted native stage are written to
**`<SD>/GHOSTBUF/PROBE/<8-hex-digits>.TXT`**. Known Sony movie folders are checked
for newly created clips and their paths are included in the report. Reports use
unique uppercase 8.3 names and are flushed before native calls, so a failed test
can still leave information about its last attempted stage.

Revision 3 also reads H.264 sequence parameter sets (SPS) from new AVCHD clips.
It accounts for coded dimensions and cropping, distinguishes 1440×1080 from
1920×1080, and supports 188-byte TS and 192-byte MTS packets. Inspection reads at
most the first 4 MiB per clip (up to four new clips), with bounded buffers and
cancellation checks. It does not invoke a native decoder or allocate full frames.

Revision 4 tests a possible alternative to direct encoder memory: reading the
growing AVCHD file from SD **while recording** and retaining its compressed bytes
in a rolling **512 KiB RAM buffer**. It uses read-only file access, checks at
100 ms intervals, and copies at most 256 KiB per interval. A separate bounded
64 KiB prefix is inspected for H.264 SPS dimensions before recorder stop. The
live reader closes before the test invokes native `stop()`; post-stop clip
inspection is reported separately. Existing movies are excluded from live reads.

The report contains a separate **Live SD access status**:

| Status | Observation during recording |
|---|---|
| **LIVE_SD_FHD** | File growth and multiple live reads were observed, and the SPS read into RAM declared 1920×1080 before stop |
| **LIVE_SD_FHD_HEADER** | A Full-HD SPS reached RAM before stop, but file growth with multiple reads was not established |
| **LIVE_SD_OTHER_SIZE** | A readable SPS declared another size |
| **LIVE_SD_BYTES** | Bytes reached RAM, but no SPS dimensions were verified in the bounded prefix |
| **LIVE_FILE_VISIBLE** | A new movie file was visible, but no bytes could be read |
| **NO_LIVE_FILE** | No new AVCHD file could be opened during the observation window |
| **LIVE_SD_READ_ERROR** | Opening the live file failed; the report includes the error |
| **NOT_STARTED** | Native recording did not reach the live-access phase |

The report records live byte counts, first/last read times, visible file length,
retained RAM bytes and CRC32, and any read error. **LIVE_SD_FHD is SD-mediated
access, not direct encoder RAM access.** It does not establish a decodable
pre-trigger clip, sustained real-time throughput, or complete ring-buffer
functionality. A negative result only describes this bounded test and route.
Revision 5 additionally reports **Live observation valid** and a separate
**Live access conclusion**. A recording or cleanup failure or cancellation
produces **INCONCLUSIVE_RECORDING_FAILED_OR_CANCELLED**, even if the observed
file status is `NO_LIVE_FILE`. The raw observations are preserved.

The native StreamBuffer capability is reported independently. When its API is
present, the probe does not allocate an undocumented buffer layout or claim
that direct RAM capture worked. Revision 4 registered `OnStreamWriteListener`
and counted its `onStarted`/`onCompleted` notifications. Revision 5 leaves this
listener disabled in both comparison modes. Those
callbacks receive the recorder, not encoded bytes; events alone never count as
successful stream access. See the
[OpenMemories listener definition](https://github.com/ma1co/OpenMemories-Framework/blob/master/stubs/src/main/jasmin/com/sony/scalar/media/MediaRecorder%24OnStreamWriteListener.j).

**RECORDER_RAN** means start and stop returned successfully with an FHD profile.
**FHD_CLIP_HEADER** additionally means a saved clip's H.264 SPS declares 1920×1080;
**CLIP_DIMENSIONS_MISMATCH** means readable SPS dimensions differ from Full HD.
Neither result verifies decoded frames or proves access to a RAM stream.
Unreadable or missing clip metadata is reported explicitly and leaves the result
at **RECORDER_RAN**. **UNAVAILABLE**, **NO_FHD_PROFILE**,
**PROFILE_REJECTED**, **ERROR**, and **CANCELLED** identify the other outcomes;
the report includes the native exception and stage when something fails.
An output-media or prepare error does not establish a hardware resolution limit.

**Up/Down** scrolls the report, **Center/Left** runs Baseline, **Right** runs Live,
and **Menu, Play,
Delete or Back** cancels and returns. Native camera/encoder calls can take time
to finish; cleanup runs before the display and parent camera are released.

This probe does not allocate a guessed StreamBuffer layout or change firmware
settings. API definitions are in the
[OpenMemories MediaRecorder stubs](https://github.com/ma1co/OpenMemories-Framework/blob/master/stubs/src/main/jasmin/com/sony/scalar/media/MediaRecorder.j).
A separate recording/streaming implementation is demonstrated by
[pmca-livestream](https://github.com/Novex/pmca-livestream), tested on newer action
cameras; that does not establish compatibility with the α5000.

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
- Built with Gradle and Android Gradle Plugin 2.2.3.
- Standard JUnit 4 test suite with 134 automated unit tests.

---

### Sony SD-card compatibility

The 8.3-name and unsupported-sync constraints are documented in [Recipe Lab’s SD-storage fix](https://github.com/voxivoid/recipe-lab-sony-pmca/commit/e5e7949). A regression test rejects every incompatible file-write path, including temporary files, and exercises captures, collisions, probes and incomplete-session recovery.

Launcher icon source and generation prompt: [assets/icon/README.md](assets/icon/README.md).

---

## License

This project is open-source under the [MIT License](LICENSE.txt) compatible with the OpenMemories ecosystem.
