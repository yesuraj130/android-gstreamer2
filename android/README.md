# GStreamer Low-Latency Android Launcher (Mobile & TV)

A production-grade Android application for low-latency live video streaming and broadcasting powered by GStreamer 1.24/1.28 and hardware-accelerated MediaCodec (`amcviddec` / `amcvidenc`).

## Features

- **3 Editable Pipeline Slots:** Edit raw `gst-launch-1.0` style pipelines with individual Play/Stop controls.
- **Auto-Play:** Automatically loads and plays the last active pipeline upon application launch.
- **Auto-Start on Boot:** Includes `RECEIVE_BOOT_COMPLETED` receiver to launch automatically when your Android TV or phone powers on.
- **Rate-Controlled Reconnection:** Handles `GST_MESSAGE_ERROR` and `GST_MESSAGE_EOS` with exponential backoff (1s $\to$ 2s $\to$ 4s $\to$ 8s $\to$ 10s max).
- **RTSP Camera Server:** Built-in `gst-rtsp-server` broadcasting on `rtsp://<device-ip>:8554/live` using hardware H.264 encoding (`amcvidenc`).
- **Android TV & Mobile Ready:**
  - Full D-pad remote focus selectors for TV.
  - Touchscreen not required (`android.hardware.touchscreen=false`).
  - Leanback launcher intent and banner.

---

## Low-Latency Pipeline Presets

### 1. Ultra Low-Latency RTSP Receiver (Hardware Accelerated)
```text
rtspsrc location=rtsp://<SERVER_IP>:8554/live latency=0 drop-on-latency=true buffer-mode=none ! rtph264depay ! h264parse ! amcviddec ! autovideosink sync=false
```

### 2. Ultra Low-Latency UDP/RTP H.264 Stream
```text
udpsrc port=5000 caps="application/x-rtp, media=(string)video, encoding-name=(string)H264" ! rtph264depay ! h264parse ! amcviddec ! autovideosink sync=false
```

### 3. Direct Camera Hardware Test (`ahc2src`)
```text
ahc2src ! video/x-raw, width=1280, height=720, framerate=30/1 ! autovideosink sync=false
```

### 4. Camera RTSP Server Broadcast
```text
( ahc2src ! video/x-raw, width=1280, height=720, framerate=30/1 ! videoconvert ! amcvidenc ! rtph264pay name=pay0 pt=96 )
```

---

## Building the APK

### Automated GitHub Actions Pipeline
Pushing to your GitHub repository triggers `.github/workflows/build.yaml` (or `build.yaml`), which:
1. Provisions JDK 17, Android SDK & NDK 26.
2. Downloads and caches the official GStreamer Android Universal SDK tarball.
3. Compiles the NDK native library (`libgst_pipeline_runner.so`) and packages the APK.
4. Uploads the generated `gst-launcher-debug-apk` as a workflow artifact.

### Local Build in Android Studio
1. Download the [GStreamer Android Universal SDK](https://gstreamer.freedesktop.org/data/pkg/android/) (version `1.28.8`).
2. Unpack to a directory (e.g. `/opt/gstreamer-android` or `/home/runner/gstreamer-android`).
3. Set the environment variable:
   ```bash
   export GSTREAMER_ROOT_ANDROID=/opt/gstreamer-android
   ```
4. Open the `android/` directory in Android Studio.
5. Sync Gradle and run **Build -> Build Bundle(s) / APK(s) -> Build APK(s)** or:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
