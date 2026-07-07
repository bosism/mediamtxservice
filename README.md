# MediaMTX Relay Service

Minimal Android foreground-service APK that runs MediaMTX on the controller without Termux.

Opening the launcher icon shows a small settings screen with an editable `mediamtx.yml`, start/stop buttons, and a log preview. ADB can also start, stop, and configure the service.

## Build

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.skydroid.mediamtxrelay.dev android.permission.POST_NOTIFICATIONS
```

## Start

Use the launcher settings screen, edit `mediamtx.yml`, then press `Start / Restart`. The default YAML is intentionally short:

```yaml
paths:
  cam:
    source: rtsp://192.168.144.25:8554/main.264
    sourceOnDemand: yes
    rtspTransport: tcp
```

ADB can start with the saved YAML:

```bash
adb shell am start \
  -n com.skydroid.mediamtxrelay.dev/com.skydroid.mediamtxrelay.MainActivity \
  --es command start
```

ADB can still generate simple YAML from extras. Use another port while Termux is still occupying `8554`:

```bash
adb shell am start \
  -n com.skydroid.mediamtxrelay.dev/com.skydroid.mediamtxrelay.MainActivity \
  --es command start \
  --ei rtsp_port 18554
```

Pull an upstream RTSP camera/source and expose it as `/cam`:

```bash
adb shell am start \
  -n com.skydroid.mediamtxrelay.dev/com.skydroid.mediamtxrelay.MainActivity \
  --es command start \
  --es source_url 'rtsp://192.168.68.10:8554/camera'
```

Then read from:

```text
rtsp://<android-device-ip>:8554/cam
```

Pass a complete YAML config directly:

```bash
adb shell am start \
  -n com.skydroid.mediamtxrelay.dev/com.skydroid.mediamtxrelay.MainActivity \
  --es command start \
  --es config_yaml "$(cat mediamtx.yml)"
```

## Stop

```bash
adb shell am start \
  -n com.skydroid.mediamtxrelay.dev/com.skydroid.mediamtxrelay.MainActivity \
  --es command stop
```

## Logs

For the debug build:

```bash
adb shell run-as com.skydroid.mediamtxrelay.dev cat files/mediamtx-service.log
```

The active MediaMTX config is:

```bash
adb shell run-as com.skydroid.mediamtxrelay.dev cat files/mediamtx.yml
```

## Bundled MediaMTX

This project packages `mediamtx` v1.19.2 for arm64 as `app/src/main/jniLibs/arm64-v8a/libmediamtx.so` so Android extracts it into the app native library directory with executable permissions.
