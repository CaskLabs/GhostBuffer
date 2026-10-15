# GhostBuffer

**GhostBuffer** is an open-source, pre-trigger rolling buffer camera application for Sony PlayMemories Camera Apps (PMCA) compatible cameras. It has been tested on the **Sony α5000 (ILCE-5000)**; other models have not yet been tested with GhostBuffer.

> **Development status — October 15, 2026:** The JPEG pre-trigger buffer is implemented. Native video tests have confirmed **1920×1080 in saved H.264 headers**, but a Full-HD video pre-trigger buffer has **not** been demonstrated. Recorder lifecycle stability remains under investigation. The latest completed software checks passed **151 unit tests**, Android lint with no errors, and APK signature verification; these checks do not establish reliability on the camera.

This README describes the current development source. The published
[v1.1 release](https://github.com/CaskLabs/GhostBuffer/releases/tag/v1.1) predates
configurable preview resolution and the video encoder/live-access tests.

---

## Overview

Traditional cameras only begin recording after the shutter is pressed, often missing spontaneous, high-speed, or unpredictable moments (e.g., lightning strikes, wildlife actions, sports milestones).

GhostBuffer maintains a rolling buffer of JPEG preview frames in memory. It polls
at up to 10 FPS and retains up to **2000 ms (2 seconds)**, subject to the memory
budget. Pressing the shutter freezes the buffered frames for background saving
to the SD card. Higher requested resolutions use lower polling targets.

| Capture path | Current evidence on the α5000 |
|---|---|
| JPEG preview buffer | 640×480 delivered; requesting 1920×1080 fell back to 640×480 |
| Native video recording to SD | Saved AVCHD clips declare 1920×1080 in H.264 SPS headers; revision 7 repeatability still needs camera testing |
| Direct encoder stream into RAM | The known `StreamBuffer` API is absent on tested firmware 1.00 / Scalar API 3 |
| Growing SD movie read into RAM | Experimental; no successful live video read has been demonstrated |

Selecting 1920×1080 in settings does not enable a Full-HD pre-trigger buffer on
this camera. The JPEG preview and native video encoder are separate data paths.

---

## Features

- **Rolling Pre-Trigger Ring Buffer:**
  - Retains up to 2 seconds of JPEG preview frames in RAM.
  - Strict byte-budget enforcement: rejects oversized incoming frames without corrupting the buffer, with a 3–8 MiB JPEG payload budget based on the app's maximum heap size and a 25-frame limit. The budget reserves space for the separate frozen snapshot and the UI; it can shorten the effective buffer duration for large JPEGs.
  - Time-window head eviction ensures oldest frames roll over seamlessly.

- **Pre-Trigger Capture:**
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
  - Pressing **Menu** opens a compact Camera / Gallery / Settings menu. Legacy demo tools are hidden.

- **Experimental Video Diagnostics:**
  - Clickable control and live-access tests, with hardware-key navigation.
  - Six-second native recording attempts, saved-clip H.264 header inspection and stage-by-stage reports.
  - Revision 7 separates native camera/recorder operations from timed waits and file reads; hardware verification is pending.

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
| Video encoder test | Open the diagnostic test screen | Open the test screen |

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
requested setting. On the tested α5000, requesting 1920×1080 still produced
640×480 JPEGs and activated the fallback. Higher-resolution JPEG delivery has
not been demonstrated. The Sony live-preview API exposes size options, but its
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

### Internal Video Encoder and Live-Access Tests (Experimental)

These tests investigate a possible Full-HD video data path. They do not change
the normal JPEG buffer into a video buffer.

Open **Fn → Settings → Video encoder test**. Opening the screen does not start
a recording. Choose a button by touch/mouse click, or use **Left/Right** to select
and **Center/Enter** to activate it.

| On-screen button | Action |
|---|---|
| **Kontrolltest** | Attempt a six-second native recording without live file reads |
| **Live-Test** | Attempt a six-second recording and read its growing SD movie into RAM |
| **Zurück** | Return to the camera while idle |
| **Abbrechen** | Cancel the running test and return |

Start buttons are disabled during a run; the return button becomes **Abbrechen**.
**Up/Down** scrolls the report. **Menu, Play, Delete or Back** cancels and returns.
For repeatability checks, run **two control tests consecutively** before trying
Live-Test. Save both control reports even if one fails.

The JPEG camera connection is released before the test opens its own connection.
The test chooses a firmware-reported Full-HD profile and reads it back before
preparing the recorder. Progressive 24p/25p profiles are preferred. On the tested
α5000 this selects **AVCHD / HD_1080 / 25p / FH / 16:9**. Sony uses `HD_1080` for
both the Full-HD AVCHD profiles and the 1440×1080 MPEG4 profile on Scalar API 3;
format and quality must be considered along with the size name. See
[Sony's movie recording settings](https://helpguide.sony.net/gbmig/44879441/v1/en/contents/TP0000301880.html).

A test can create a normal movie, including audio, in the camera's standard SD
movie folders. The app declares microphone permission for native recording.
Cleanup attempts to restore original recorder parameters and release native
resources, recording any failures. Reports are saved to **`<SD>/GHOSTBUF/PROBE/<8-hex-digits>.TXT`** and
include the app version, camera/firmware, Scalar API version, attempted stages,
exceptions, callback times/threads, activity state and new clip paths. Report
names use uppercase 8.3 format and stages are flushed before native calls.

#### Current Revision: 7

All native camera and recorder operations run on one dedicated event thread,
including construction, configuration, preview, start/stop and release. A
separate coordinator handles waits, reports and SD reads. This keeps timed
recording waits from occupying the camera's event loop. Callback threading is
recorded so it can be checked on hardware. The design follows Android's
[single-event-thread camera guidance](https://developer.android.com/reference/android/hardware/Camera)
and [recorder callback Looper requirements](https://developer.android.com/reference/android/media/MediaRecorder);
Sony-specific reliability remains to be verified.

When the record-state listener is available, both modes wait up to **two seconds**
for `onStarted`. Live reads require that callback plus **500 ms** settling time.
The live test predicts up to four next five-digit AVCHD filenames **before**
recording and attempts read-only access to those exact paths every **250 ms**.
It performs no directory enumeration during recording. Missing start confirmation
skips live reads; missing predicted files do not establish a general access ban.

Live reads retain at most **512 KiB** of compressed data in a rolling RAM buffer,
with a separate **64 KiB** prefix for H.264 SPS inspection and at most **256 KiB**
read per sampling interval. The live reader closes before native stop. Saved
clips are inspected separately after cleanup, reading at most their first
**4 MiB** to determine H.264 dimensions, including cropping. This does not decode
frames or measure the actual video frame rate.

The known native `StreamBuffer` API is checked independently. Its absence on the
α5000 leaves direct encoder-to-RAM capture unavailable through that tested API.
The app does not allocate an undocumented stream buffer when the API is present.
The stream-write listener is disabled: its `onStarted`/`onCompleted` notifications
carry a recorder reference, not encoded video bytes. API definitions are in the
[OpenMemories recorder stubs](https://github.com/ma1co/OpenMemories-Framework/blob/master/stubs/src/main/jasmin/com/sony/scalar/media/MediaRecorder.j)
and [stream-write listener stub](https://github.com/ma1co/OpenMemories-Framework/blob/master/stubs/src/main/jasmin/com/sony/scalar/media/MediaRecorder%24OnStreamWriteListener.j).

#### Reading Results

| Recorder result | Meaning |
|---|---|
| **FHD_CLIP_HEADER** | A newly saved clip's H.264 SPS declares 1920×1080 |
| **RECORDER_RAN** | Start/stop returned successfully; saved dimensions were not verified |
| **CLIP_DIMENSIONS_MISMATCH** | Saved SPS dimensions differ from 1920×1080 |
| **START_UNCONFIRMED** | No registered `onStarted` callback arrived within two seconds |
| **UNAVAILABLE / NO_FHD_PROFILE / PROFILE_REJECTED** | Recorder API or a recognized/retained Full-HD profile was unavailable |
| **ERROR / CANCELLED** | A stage failed or the run was cancelled; inspect its details |

The **Live SD access status** is a separate observation:

| Live status | Meaning before native stop |
|---|---|
| **LIVE_SD_FHD** | The file grew, multiple reads reached RAM, and its SPS declared 1920×1080 |
| **LIVE_SD_FHD_HEADER** | A Full-HD SPS reached RAM, but growth with multiple reads was not established |
| **LIVE_SD_OTHER_SIZE** | A readable SPS declared another size |
| **LIVE_SD_BYTES** | Bytes reached RAM; SPS dimensions were not verified |
| **LIVE_FILE_VISIBLE** | A movie opened, but no bytes could be read |
| **NO_LIVE_FILE** | No eligible predicted AVCHD path could be opened |
| **LIVE_SD_READ_ERROR / NOT_STARTED** | Opening failed, or the live-access phase was not initialized |

Always check **Live access conclusion**, **Live observation valid**, and
**Live access phase entered** alongside byte counts. A failed/cancelled recording
is **INCONCLUSIVE_RECORDING_FAILED_OR_CANCELLED**. Missing start confirmation is
**INCONCLUSIVE_START_NOT_CONFIRMED**. Neither is evidence that healthy live video
is unreadable. **LIVE_SD_FHD would confirm SD-mediated access, not direct encoder
RAM access or an independently decodable pre-trigger clip.**

#### Hardware Evidence and Open Work

Saved α5000 clips have repeatedly declared **1920×1080** in their SPS headers,
including successful six-second controls. In five revision 6 reports, two control
runs succeeded, two controls failed at native stop, and one live run failed
before making any file-open attempt. Failed runs lacked `onStarted` and ended
with the activity paused. Thus failures also occurred without live SD access.

Revision 7 addresses the native event-thread arrangement, but its camera results
are still pending. Reliable repeated recording, successful live reads, actual
decoded video frames and a complete Full-HD pre-trigger buffer remain open.
The independent [pmca-livestream project](https://github.com/Novex/pmca-livestream)
demonstrates streaming on newer action cameras; this does not establish α5000
compatibility.

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

Example values; actual app version, dimensions, frame rate and timing are recorded
for each saved session.

```json
{
  "version": 1,
  "app_version": "development-build",
  "camera_model": "ILCE-5000",
  "camera_firmware": "1.00",
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

1. Download [GhostBuffer-v1.1.apk](https://github.com/CaskLabs/GhostBuffer/releases/download/v1.1/GhostBuffer-v1.1.apk) from the [v1.1 release](https://github.com/CaskLabs/GhostBuffer/releases/tag/v1.1). Checksums are available in [SHA256SUMS.txt](https://github.com/CaskLabs/GhostBuffer/releases/download/v1.1/SHA256SUMS.txt).
2. Download and launch [Sony-PMCA-RE GUI](https://github.com/ma1co/Sony-PMCA-RE/releases).
3. Connect your camera via USB in "Mass Storage" mode.
4. Select the "Install an app" tab, choose the downloaded APK file, and click "Install".

### Method 2: Command Line

With [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) available locally:

```bash
python pmca-console.py install -f GhostBuffer-v1.1.apk
```

For the current resolution picker and video diagnostics, install an APK built
from the development source instead. Select its exact filename when installing;
the published v1.1 APK does not include those later additions.

---

## Building from Source

### Requirements

- **JDK 8** (OpenJDK 8 or Eclipse Temurin 8)
- **Android SDK** with Platform 10 (Gingerbread 2.3.3) and Build Tools 25.0.2
- **Git**, used to derive the APK version name and code

Set `JAVA_HOME` to JDK 8 and `ANDROID_HOME` to the SDK directory, or set the SDK
path in an untracked `local.properties` file (`sdk.dir=/path/to/android-sdk`).
The repository includes a Gradle 3.3 wrapper and uses Android Gradle Plugin 2.2.3.

### Build Commands

```bash
git clone https://github.com/CaskLabs/GhostBuffer.git
cd GhostBuffer

# Test, lint and build a debug APK
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

# app/build/outputs/apk/GhostBuffer-debug-<git-version>.apk
```

### Running Automated Tests

```bash
./gradlew --no-daemon testDebugUnitTest
```

<details>
<summary>Legacy dependency repository workaround</summary>

The development APKs were also verified with **Gradle 2.14.1**, JDK 8 and the
following init script to replace legacy JCenter requests with a mirror. If
dependency resolution stalls at JCenter, save this as `repositories.gradle`:

```groovy
allprojects {
    buildscript.repositories {
        maven { url 'https://repo.maven.apache.org/maven2' }
        maven { url 'https://maven.aliyun.com/repository/jcenter' }
        whenObjectAdded { repo ->
            if (repo.name == 'BintrayJCenter') remove(repo)
        }
    }
    repositories {
        maven { url 'https://repo.maven.apache.org/maven2' }
        maven { url 'https://maven.aliyun.com/repository/jcenter' }
        whenObjectAdded { repo ->
            if (repo.name == 'BintrayJCenter') remove(repo)
        }
    }
}
```

Run an installed Gradle 2.14.1 with that script:

```bash
gradle --no-daemon --console plain -I repositories.gradle testDebugUnitTest lintDebug assembleDebug
```

</details>

---

## Camera Compatibility

GhostBuffer has been tested on the **Sony α5000 (ILCE-5000)**. The native video
reports discussed here came from **firmware 1.00, Scalar API 3, Android API 10**.

Other PMCA-compatible models may work but have not yet been tested with
GhostBuffer. Available preview sizes, recorder APIs and exposure controls depend
on the model, firmware and lens.

---

## Framework & Dependencies

- Uses Sony PMCA Scalar APIs provided by [OpenMemories: Framework](https://github.com/ma1co/OpenMemories-Framework).
- Built with Gradle and Android Gradle Plugin 2.2.3.
- Standard JUnit 4 test suite with 151 automated unit tests.

---

### Sony SD-card compatibility

The 8.3-name and unsupported-sync constraints are documented in [Recipe Lab’s SD-storage fix](https://github.com/voxivoid/recipe-lab-sony-pmca/commit/e5e7949). A regression test rejects every incompatible file-write path, including temporary files, and exercises captures, collisions, probes and incomplete-session recovery.

Launcher icon source and generation prompt: [assets/icon/README.md](assets/icon/README.md).

---

## License

This project is open-source under the [MIT License](LICENSE.txt) compatible with the OpenMemories ecosystem.
