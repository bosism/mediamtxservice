# MediaMTX Relay Service

Minimal Android foreground-service APK that runs MediaMTX on the controller without Termux.

The app has no real UI. Opening the launcher icon starts the foreground service and then closes the activity. ADB can also start, stop, and configure the service.

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

Default RTSP port is `8554`:

```bash
adb shell am start -n com.skydroid.mediamtxrelay.dev/com.skydroid.mediamtxrelay.MainActivity
```

Use another port while Termux is still occupying `8554`:

```bash
adb shell am start \
  -n com.skydroid.mediamtxrelay.dev/com.skydroid.mediamtxrelay.MainActivity \
  --ei rtsp_port 18554
```

Pull an upstream RTSP camera/source and expose it as `/relay`:

```bash
adb shell am start \
  -n com.skydroid.mediamtxrelay.dev/com.skydroid.mediamtxrelay.MainActivity \
  --es source_url 'rtsp://192.168.68.10:8554/camera'
```

Then read from:

```text
rtsp://<android-device-ip>:8554/relay
```

If no `source_url` is provided, MediaMTX accepts publisher clients on any path and relays them to readers.

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

The generated MediaMTX config is:

```bash
adb shell run-as com.skydroid.mediamtxrelay.dev cat files/mediamtx.yml
```

## Bundled MediaMTX

This project packages `mediamtx` v1.19.2 for arm64 as `app/src/main/jniLibs/arm64-v8a/libmediamtx.so` so Android extracts it into the app native library directory with executable permissions.
