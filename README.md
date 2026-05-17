# DroidDashCam

DroidDashCam is a high-performance Android dashboard camera application designed for modern driving needs. It supports simultaneous dual-camera recording, real-time RTMP streaming, and remote monitoring from other Android devices.

## Features

- **Dual Camera Support**: Capture video from both the front and back cameras simultaneously (requires Android 11+ and hardware support for `FEATURE_CAMERA_CONCURRENT`).
- **High-Quality Recording**: Local video storage using modern MediaStore APIs, compatible with Scoped Storage.
- **Real-Time RTMP Streaming**: Publish your dashcam feed to a remote server for live monitoring.
- **Remote Viewer**: Use the built-in viewer on another Android device to see what your dashcam is recording in real-time.
- **Camera Selection**: Remotely switch between the front and back camera views in the viewer app.
- **Debian Bridge Support**: Includes a setup script for a MediaMTX bridge server to facilitate remote connections.

## Project Structure

- `app/`: The main Android application module.
- `server_setup.sh`: A shell script to set up a MediaMTX RTMP/RTSP bridge on a Debian/Ubuntu server.

## Getting Started

### Prerequisites

- Android Studio Meerkat or newer (recommended).
- JDK 21.
- An Android device running API 21+ (Android 5.0).
  - *Note: Dual camera features require Android 11+ and compatible hardware.*

### Building and Installation

To build the project from the command line:

```bash
chmod +x gradlew
./gradlew assembleDebug
```

The resulting APK will be located at `app/build/outputs/apk/debug/app-debug.apk`. You can install it on your device using:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Streaming Setup

To use the remote monitoring feature, you need an RTMP bridge server.

### 1. Set up the Debian Server
Upload `server_setup.sh` to your Debian/Ubuntu server and run it:

```bash
chmod +x server_setup.sh
./server_setup.sh
```

The script will install **MediaMTX** and output your server's IP address and the necessary URLs.

### 2. Configure the DashCam
1. Open DroidDashCam on your "camera" phone.
2. In the **RTMP URL** field, enter your server's base URL (e.g., `rtmp://your-server-ip/live`).
3. Press **Start Stream**. The app will publish two feeds:
   - `.../live/back` (Main camera)
   - `.../live/front` (Front camera)

### 3. Use the Remote Viewer
1. Open DroidDashCam on your "viewer" phone.
2. Click **View Remote Dashcam**.
3. Enter the same base URL (e.g., `rtmp://your-server-ip/live`).
4. Select which camera you want to see (Back/Front) and press **Play**.

## Permissions

The app requires the following permissions:
- `CAMERA`: To capture video.
- `RECORD_AUDIO`: To capture sound.
- `WRITE_EXTERNAL_STORAGE`: For saving videos on older Android versions.
- `INTERNET` & `ACCESS_WIFI_STATE`: For remote streaming.

## Technical Stack

- **Language**: Kotlin 1.9.22
- **Camera API**: Jetpack CameraX 1.4.0
- **Streaming**: RootEncoder (pedroSG94) 2.4.5
- **Playback**: AndroidX Media3 (ExoPlayer) 1.4.1
- **Build System**: Gradle 8.8 / AGP 8.5.0
