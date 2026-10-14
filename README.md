# GhostBuffer

**GhostBuffer** is an open-source, pre-trigger rolling buffer camera application for Sony PlayMemories Camera Apps (PMCA) compatible mirrorless cameras, specifically targeting the **Sony α5000 (ILCE-5000)**.

> **V1 review (14 October 2026):** The debug build and 21 host unit tests pass, but release-blocking lifecycle and data-integrity issues remain. Camera compatibility and performance have not been verified in this review. See [Review der V1](#review-der-v1) for findings and validation limits.

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

## Review der V1

**Stand:** 14. Oktober 2026. Geprüft wurde `ed41c7b` mit der V1-Implementierung aus `fd56731`. Umfang: Quellcode der Aufnahme-Pipeline, Oberfläche, Galerie, Probe, Speicherverwaltung, Build-Konfiguration und vorhandene Tests. Es wurden keine Implementierungsfehler behoben; dieses Review dokumentiert den Handlungsbedarf.

**Bewertung:** Die V1 besitzt eine brauchbare Trennung von Backend, Ringpuffer, Controller und Writer. Zeitfensterfilterung beim Trigger, unabhängige Snapshot-Kopien, Auslöser-Latch und Freigabe aller zurückgegebenen `DeviceMemory`-Objekte im `finally` sind gute Grundlagen. Für eine belastbare Freigabe auf der A5000 bleiben jedoch die folgenden Fehler und Hardware-Nachweise offen. Die Produktbeschreibungen oben sind keine Hardware-Abnahme.

### Prüfung und Aussagegrenzen

- Mit dem lokal vorhandenen JDK 8, Android SDK 10 und Build Tools 25.0.2 wurde `JAVA_HOME=/home/agent/tools/jdk8 PATH=/home/agent/tools/jdk8/bin:$PATH ./gradlew testDebugUnitTest assembleDebug --offline` erfolgreich ausgeführt. **21 Tests, 0 Fehler, 0 übersprungen.** Der Offline-Build bestätigt den vorhandenen Cache, keinen sauberen Erstbuild auf einem anderen Rechner.
- Zusätzliche temporäre JVM-Prüfungen gegen die gebauten Klassen bestätigten: Probe erfolgreich trotz nicht dekodierbarer JPEG-Datei; Annahme eines 240-Byte-Frames bei 100-Byte-Budget; unsichtbare Sitzung ohne Manifest; `BUFFERING` mit `frameReader == null` nach `pause()` und `resumeBuffering()` sowie nach simuliertem spätem Save-Callback; falsches UTC-Suffix bei einer anderen Zeitzone. Diese Prüfprogramme wurden nicht ins Repository aufgenommen.
- Keine Sony-Kamera wurde angeschlossen. Installation, tatsächliche Sony-API-Semantik, Autofokus, neue statt wiederholter Frames, SD-Verhalten, Speicherverbrauch, Tasten und Langzeitstabilität wurden nicht auf Hardware geprüft. Hosttests mit Fake-Backend ersetzen diese Nachweise nicht.

### Befunde nach Priorität

**P1 = vor einer verlässlichen V1-Freigabe beheben; P2 = relevante Funktions- oder Qualitätslücke.** „Codebefund“ bezeichnet aus dem Quellcode abgeleitete Abläufe; Hardwarefolgen sind dabei noch nicht gemessen.

1. **P1 – Späte Save-Callbacks können einen pausierten Controller wieder aktiv anzeigen.**

   Quellen: [CaptureController.java](app/src/main/java/com/casklabs/ghostbuffer/capture/CaptureController.java), `pause`, `start`, `onSaveComplete`, `resumeBuffering`; [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), `onSaveComplete`.

   Ablauf: Während des Speicherns lässt sich die Galerie öffnen. `pause()` entfernt den Reader und schließt das Backend, lässt aber `SAVING` bestehen. Kommt der Writer danach zurück, setzt er ohne Prüfung `REVIEW`; der verzögerte UI-Callback ruft anschließend `resumeBuffering()` auf. Diese Methode setzt auch ohne Reader `BUFFERING`. Bei einer schnellen Rückkehr während `SAVING` überspringt `start()` zusätzlich die Initialisierung. Der Teilablauf mit spätem Callback wurde auf der JVM bestätigt. **Folge:** Die UI kann Aufnahmebereitschaft melden, obwohl keine Frames gelesen werden. **Maßnahme:** Vordergrundstatus und Laufgeneration prüfen, alte Callbacks entkoppeln, UI-Aufträge bei Pause entfernen und Kamera/Reader vor `BUFFERING` vollständig neu initialisieren. **Abnahme:** Galerie während langsamer Speicherung öffnen, dort bleiben und mehrfach zurückkehren; kein Puffern im Hintergrund und zuverlässig neue Frames nach Rückkehr.

2. **P1 – Kamera-Zugriffe sind nicht serialisiert; Reader-Stopp garantiert kein Ende.**

   Quellen: [FrameReader.java](app/src/main/java/com/casklabs/ghostbuffer/preview/FrameReader.java), `stop`; [SonyPreviewBackend.java](app/src/main/java/com/casklabs/ghostbuffer/preview/SonyPreviewBackend.java), `readNextFrame`, `close`; [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), `runProbe`, `onPause`.

   Codebefund: Fn startet einen weiteren Thread, der parallel zum Reader dasselbe Backend und denselben direkten ByteBuffer nutzt. Mehrfaches Fn erlaubt weitere Threads. `stop()` wartet höchstens 500 ms und verwirft dann die Threadreferenz; anschließend können Sequence und Kamera geschlossen werden, obwohl ein nativer Leseaufruf noch läuft. Ein erneuter Start teilt zudem das `running`-Flag mit dem alten Thread. **Folge:** Mögliche korrupte Frames, konkurrierende native Zugriffe und Freigabe noch benutzter Ressourcen. **Maßnahme:** Ein Besitzerthread für Kamera, Sequence und Probe; Ende bestätigen, bevor Ressourcen freigegeben oder neue Reader gestartet werden. **Abnahme:** Blockierenden Backend-Aufruf simulieren und Pause, Neustart sowie wiederholtes Fn prüfen; Hardware-Abbruchverhalten separat messen.

3. **P1 – Die Kamera-Sequenz startet vor einer bestätigten Livebild-Surface.**

   Quelle: [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), `onResume`, `surfaceCreated`, `surfaceDestroyed`.

   Codebefund: `onResume()` startet den Controller direkt nach dem Kameraöffnen. Preview-Display und normales Preview werden erst in `surfaceCreated()` eingerichtet; bei Surface-Verlust wird nur das normale Preview gestoppt. **Folge:** Die Initialisierung hängt von der Callback-Reihenfolge ab, während der Reader bei verlorener Surface weiterlaufen kann. **Maßnahme:** Initialisierung an Vordergrund, gültige Surface und gestartetes Preview binden; Surface-Verlust in den geordneten Stopp aufnehmen und Ressourcen auch nach fehlgeschlagener Initialisierung freigeben. **Abnahme:** Start, Surface-Neuerstellung und Pause/Wiederaufnahme auf der A5000 wiederholt prüfen.

4. **P1 – Kamerafehler können unbemerkt auf synthetische Aufnahmen umschalten.**

   Quellen: [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), Backend-Auswahl und `onResume`; [FakePreviewBackend.java](app/src/main/java/com/casklabs/ghostbuffer/preview/FakePreviewBackend.java); [GhostProbeManager.java](app/src/main/java/com/casklabs/ghostbuffer/probe/GhostProbeManager.java).

   Bei fehlender Sequence-Klasse oder fehlgeschlagenem `CameraEx.open()` wird automatisch das Fake-Backend verwendet. Es erzeugt JPEG-Marker und Dummybytes, aber kein dekodierbares Bild. Dennoch prüft die Probe nur den SOI-Anfang und meldet Erfolg; der Writer kann solche Daten als vollständige Sitzung speichern. JVM-Nachweis: `Probe success=true`, aber `ImageIO.read` liefert kein Bild bzw. schlägt fehl. **Maßnahme:** Simulation ausdrücklich aktivierbar und sichtbar machen; auf Kamerafehler mit Fehlerstatus reagieren. Die Hardwareprobe muss echte dekodierbare, veränderliche Bilder nachweisen. **Abnahme:** Kameraöffnungsfehler darf keine scheinbar echte Aufnahme erzeugen; exportierte Probe vollständig dekodieren.

5. **P1 – Unterbrochene Sitzungen verschwinden aus der Galerie; Persistenz ist nur teilweise abgesichert.**

   Quellen: [CaptureWriter.java](app/src/main/java/com/casklabs/ghostbuffer/capture/CaptureWriter.java), `writeSnapshot`; [CaptureSession.java](app/src/main/java/com/casklabs/ghostbuffer/capture/CaptureSession.java), `loadAllSessions`; [GhostGalleryActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostGalleryActivity.java).

   Das Manifest entsteht erst nach allen JPEGs. Bricht ein früher Write ab, fehlen Manifest und `COMPLETE`. Der Loader nimmt ausschließlich Sitzungen mit lesbarem Manifest auf; eine solche Sitzung wurde im JVM-Versuch nicht angezeigt. Auch geladene unvollständige Sitzungen werden in der Galerie nicht als unvollständig markiert. JPEGs werden synchronisiert und umbenannt, Manifest und Marker jedoch nicht entsprechend synchronisiert; ein Marker allein verifiziert keine vorhandenen, intakten Frames. **Maßnahme:** Unvollständige Verzeichnisse sichtbar machen, Status in der Galerie anzeigen, Manifest atomar schreiben und Daten vor Abschluss soweit unterstützt synchronisieren sowie beim Laden prüfen. **Abnahme:** Schreibfehler nach dem ersten JPEG, beschädigtes Manifest und fehlenden Frame simulieren; kein falscher Erfolg und keine versteckten Restdaten. Stromausfallverhalten bleibt ein SD-Hardwaretest.

6. **P2 – Das Bytebudget begrenzt weder jeden Insert noch den Gesamtspeicher.**

   Quellen: [FrameRingBuffer.java](app/src/main/java/com/casklabs/ghostbuffer/buffer/FrameRingBuffer.java), `push`, `freezeSnapshot`; [FrameSlot.java](app/src/main/java/com/casklabs/ghostbuffer/buffer/FrameSlot.java).

   Ein Frame größer als das Budget wird nach Entfernen aller bisherigen Frames trotzdem eingefügt: JVM-Nachweis 240 Bytes bei 100 Bytes Budget. Die aktuelle Sony-Quelle begrenzt einzelne Frames auf 256 KiB, sodass dieser konkrete Überlauf mit dem Standardbudget dort nicht erreicht wird. Unabhängig davon verdoppelt `freezeSnapshot()` die JPEG-Arrays vorübergehend; der ursprüngliche Ring bleibt während des Speicherns bestehen. Direkter Buffer, Bitmaps und native Kameraressourcen kommen hinzu. **Maßnahme:** Übergrößen und ungültige Längen ablehnen, tatsächliche Arraybelegung berücksichtigen und Snapshot-/Ringbesitz gemeinsam budgetieren. **Abnahme:** Grenzfälle testen und Spitzenverbrauch beim Trigger auf der Kamera messen; 3 MiB nicht als OOM-Garantie darstellen.

7. **P2 – Fehlerzustände haben keinen vollständigen bedienbaren Wiederherstellungsweg.**

   Quellen: [CaptureController.java](app/src/main/java/com/casklabs/ghostbuffer/capture/CaptureController.java), `onReaderError`, `retrySave`, `resumeBuffering`; [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), Tastenbelegung und Statistik-Callbacks.

   Codebefund: Nach Lesefehlern bleibt der Reader aktiv, der Controller bleibt aber in `ERROR`. Bei dauerhaft leeren Frames erfolgt gar kein Fehler-/Stale-Status, die letzte Statistik bleibt stehen. `retrySave()` existiert, wird von der UI jedoch nicht aufgerufen. Galerie öffnen und zurückkehren kann neu starten, ohne den behaltenen fehlgeschlagenen Snapshot ausdrücklich zu sichern oder zu verwerfen. **Maßnahme:** Readerfehler und Frame-Ausfälle definiert behandeln; Wiederholen und bewusstes Verwerfen über Kameratasten anbieten. **Abnahme:** Leere Frames, Lesefehler und volle Karte simulieren; Status muss den tatsächlichen Zustand wiedergeben und jeder Fehler einen erreichbaren Ausweg besitzen.

8. **P2 – Manifest enthält falsche App-Version und potenziell falsche UTC-Zeit.**

   Quellen: [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), `onCreate`; [GhostStorage.java](app/src/main/java/com/casklabs/ghostbuffer/storage/GhostStorage.java), `formatIso8601`; [app/build.gradle](app/build.gradle).

   `setAppVersion(getDeviceInfo().getFirmwareVersion())` schreibt die Kamera-Firmware in `app_version`. Die ISO-Funktion verwendet die lokale Zeitzone, hängt aber ein wörtliches `Z` an. JVM-Nachweis bei GMT+02:00: Epoch 0 wird `1970-01-01T02:00:00.000Z`. **Maßnahme:** App-Version aus `BuildConfig.VERSION_NAME`, Firmware separat und Zeit explizit in UTC schreiben. **Abnahme:** Metadaten mit APK-Version vergleichen und mindestens zwei Zeitzonen testen.

9. **P2 – Galerie kann bei fehlenden Frames das vorherige Bild anzeigen.**

   Quelle: [GhostGalleryActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostGalleryActivity.java), `displayCurrentFrame`, `deleteRecursive`.

   Codebefund: Existiert die ausgewählte Datei nicht, wird das bisherige Bild nicht geleert, während Beschriftung und Zeitoffset auf den neuen Frame wechseln. Session-Scan, Dekodierung und rekursives Löschen laufen zudem auf dem UI-Thread; Löschfehler werden ignoriert. **Maßnahme:** Bild beim Wechsel zunächst leeren, fehlende/beschädigte Frames sichtbar kennzeichnen, SD-Arbeiten auslagern und Löschresultate melden. **Abnahme:** Eine referenzierte Datei entfernen, zwischen Frames wechseln und langsame bzw. nicht beschreibbare Karte simulieren.

10. **P2 – Build und dokumentierte Bedienung sind noch nicht reproduzierbar bzw. vollständig belegt.**

    Quellen: [app/build.gradle](app/build.gradle), [AndroidManifest.xml](app/src/main/AndroidManifest.xml), [GhostBufferActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostBufferActivity.java), [GhostGalleryActivity.java](app/src/main/java/com/casklabs/ghostbuffer/ui/GhostGalleryActivity.java).

    Framework und Stubs verwenden bewegliche `-SNAPSHOT`-Versionen. Die APK-Version wird aus Git abgeleitet; beim Review entstand `GhostBuffer-debug-1.0.0-1-ged41c7b.apk`, daher ist der oben genannte Dateiname kein festes Buildergebnis. Das Diagnose-Menü ist zwar deklariert, aber vom Launcher und den GhostBuffer-Tasten aus nicht erreichbar. Die Galerie implementiert Richtungstasten, keine eigene Dial-Navigation. `LICENSE.txt` enthält MIT; die README-Angabe „MIT / Apache 2.0“ ist dadurch nicht belegt. **Maßnahme:** Geprüfte Abhängigkeiten fixieren, einen sauberen Erstbuild nachweisen, Versions-/Dateinamen erklären und Bedienungs- sowie Lizenzangaben mit dem tatsächlichen Stand abgleichen.

### Empfohlene Abnahme vor Freigabe

Zuerst Lifecycle, Serialisierung, Simulation und Wiederherstellung unvollständiger Sitzungen absichern (P1). Anschließend die P2-Fälle durch gezielte Tests abdecken. Die bestehenden Tests prüfen überwiegend Erfolgsabläufe mit synthetischen Daten; sie decken weder blockierende Sony-Aufrufe noch echte Bilddekodierung, UI-Lifecycle oder frühe Schreibabbrüche ab.

Danach die Hardware-Abnahme aus [GHOSTBUFFER_PLAN.md](GHOSTBUFFER_PLAN.md) dokumentieren: Modell/Firmware und Installation; mindestens zehn dekodierbare, veränderliche Probe-JPEGs aus zwei Starts; gemessene neue Frames/s und Latenz; 50 Sicherungen einschließlich langem Tastendruck; fehlende/volle/entfernte SD-Karte; wiederholte Pause und Galerie; mindestens 30 Minuten Betrieb mit Speicherverlauf. Die Zeitoffsets im Manifest beziehen sich auf das Ende des App-Lesevorgangs, nicht auf nachgewiesene Sensor-Belichtungszeitpunkte. VGA-Vorschauframes, die tatsächliche verfügbare Pufferdauer und die Aufnahmelücke während Speicherung/Galerie müssen als Grenzen der V1 beschrieben bleiben.
