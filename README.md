# ProDashcam

ProDashcam is a professional-grade Android dashboard camera application. It features simultaneous dual-camera recording, background execution via a high-priority Foreground Service, real-time RTMP streaming, and an advanced dashboard UI with telemetry gauges.

## Pro Features

- **Background Recording**: Captures video seamlessly even when the app is minimized, the screen is off, or the device is locked.
- **Dual Camera Capture**: Simultaneous recording from both front and back cameras (requires hardware support).
- **Pro Dashboard**: Real-time circular gauges for GPS speed and G-sensor telemetry.
- **Remote Monitoring**: Built-in RTMP streaming engine and remote viewer with camera selection.
- **Loop Recording**: Automatically manages storage by segmenting clips (1, 3, 5, 10 min) and cleaning up old files.
- **Security**: PIN-protected UI lock (default 0000) and automatic impact detection for emergency clip protection.
- **Quick Controls**: Home screen widget and Quick Settings tile for one-touch operation.
- **Telemetry Sync**: GPS and sensor data are recorded and synchronized with video playback in Review mode.
- **Modern Android Support**: Fully optimized for Android 11 to 16, featuring edge-to-edge UI and Scoped Storage compliance.

## Getting Started

### Prerequisites

- JDK 21
- Android device running API 24+
- (Optional) Debian/Ubuntu server for RTMP bridge

### Building

```bash
./gradlew assembleDebug
```

Binaries:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release (Unsigned): `app/build/outputs/apk/release/app-release-unsigned.apk`

## Installation

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Technical Architecture

- **Engine**: CameraX + RootEncoder
- **DI**: Hilt
- **DB**: Room
- **Persistence**: Foreground Service + LifecycleRegistry
- **UI**: Material 3 + ViewBinding
