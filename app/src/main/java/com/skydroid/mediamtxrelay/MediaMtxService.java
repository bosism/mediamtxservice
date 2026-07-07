package com.skydroid.mediamtxrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class MediaMtxService extends Service {
    public static final String ACTION_START = "com.skydroid.mediamtxrelay.START";
    public static final String ACTION_STOP = "com.skydroid.mediamtxrelay.STOP";
    public static final String ACTION_RESTART = "com.skydroid.mediamtxrelay.RESTART";

    public static final String EXTRA_RTSP_PORT = "rtsp_port";
    public static final String EXTRA_SOURCE_URL = "source_url";
    public static final String EXTRA_TCP_ONLY = "tcp_only";

    private static final String CHANNEL_ID = "mediamtx_relay";
    private static final int NOTIFICATION_ID = 7104;
    private static final int DEFAULT_RTSP_PORT = 8554;
    private static final int MAX_FAST_FAILURES = 5;
    private static final long FAST_FAILURE_WINDOW_MS = 30_000L;
    private static final long LOG_MAX_BYTES = 512L * 1024L;

    private final Object lock = new Object();
    private Process process;
    private RelayConfig currentConfig = RelayConfig.defaults();
    private boolean stopping;
    private int fastFailures;
    private long firstFailureAt;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopRelay();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        RelayConfig config = RelayConfig.fromIntent(intent);
        startInForeground(getString(R.string.notification_starting), config.rtspPort);

        if (ACTION_RESTART.equals(action) || !currentConfig.sameAs(config)) {
            stopRelay();
        }

        currentConfig = config;
        stopping = false;
        startRelay(config);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRelay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startRelay(RelayConfig config) {
        synchronized (lock) {
            if (process != null && process.isAlive()) {
                updateNotification(getString(R.string.notification_running, config.rtspPort), config.rtspPort);
                return;
            }

            try {
                File configFile = writeConfig(config);
                File binary = new File(getApplicationInfo().nativeLibraryDir, "libmediamtx.so");

                ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), configFile.getAbsolutePath());
                builder.directory(getFilesDir());
                builder.redirectErrorStream(true);
                process = builder.start();

                appendLog("started " + binary.getAbsolutePath() + " " + configFile.getAbsolutePath());
                Thread logThread = new Thread(() -> drainOutput(process), "mediamtx-log");
                logThread.setDaemon(true);
                logThread.start();

                Thread supervisorThread = new Thread(() -> supervise(process), "mediamtx-supervisor");
                supervisorThread.setDaemon(true);
                supervisorThread.start();

                updateNotification(getString(R.string.notification_running, config.rtspPort), config.rtspPort);
            } catch (IOException e) {
                appendLog("start failed: " + e);
                updateNotification("Start failed: " + e.getMessage(), config.rtspPort);
                stopSelf();
            }
        }
    }

    private void supervise(Process watchedProcess) {
        int exitCode;
        try {
            exitCode = watchedProcess.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        synchronized (lock) {
            if (process == watchedProcess) {
                process = null;
            }
        }

        appendLog("process exited with code " + exitCode);
        if (stopping) {
            return;
        }

        long now = System.currentTimeMillis();
        if (firstFailureAt == 0L || now - firstFailureAt > FAST_FAILURE_WINDOW_MS) {
            firstFailureAt = now;
            fastFailures = 1;
        } else {
            fastFailures++;
        }

        if (fastFailures > MAX_FAST_FAILURES) {
            appendLog("giving up after repeated fast failures");
            updateNotification(getString(R.string.notification_failed), currentConfig.rtspPort);
            stopSelf();
            return;
        }

        try {
            Thread.sleep(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!stopping) {
            startRelay(currentConfig);
        }
    }

    private void drainOutput(Process activeProcess) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(activeProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(line);
            }
        } catch (IOException e) {
            appendLog("log read failed: " + e);
        }
    }

    private void stopRelay() {
        stopping = true;
        Process toStop;
        synchronized (lock) {
            toStop = process;
            process = null;
        }

        if (toStop == null) {
            return;
        }

        appendLog("stopping process");
        toStop.destroy();
        try {
            if (!toStop.waitFor(3, TimeUnit.SECONDS)) {
                appendLog("forcing process stop");
                toStop.destroyForcibly();
                toStop.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            toStop.destroyForcibly();
        }
    }

    private File writeConfig(RelayConfig config) throws IOException {
        File file = new File(getFilesDir(), "mediamtx.yml");
        String yaml = buildConfig(config);
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private String buildConfig(RelayConfig config) {
        String transports = config.tcpOnly ? "[tcp]" : "[udp, multicast, tcp]";
        StringBuilder builder = new StringBuilder();
        builder.append("logLevel: info\n");
        builder.append("logDestinations: [stdout]\n");
        builder.append("api: false\n");
        builder.append("metrics: false\n");
        builder.append("pprof: false\n");
        builder.append("playback: false\n");
        builder.append("rtsp: true\n");
        builder.append("rtspTransports: ").append(transports).append('\n');
        builder.append("rtspAddress: :").append(config.rtspPort).append('\n');
        builder.append("rtmp: false\n");
        builder.append("hls: false\n");
        builder.append("webrtc: false\n");
        builder.append("srt: false\n");
        builder.append("moq: false\n");
        builder.append("paths:\n");
        if (config.sourceUrl != null && !config.sourceUrl.isEmpty()) {
            builder.append("  relay:\n");
            builder.append("    source: ").append(yamlQuote(config.sourceUrl)).append('\n');
            builder.append("    rtspTransport: tcp\n");
            builder.append("    sourceOnDemand: false\n");
        }
        builder.append("  all_others:\n");
        return builder.toString();
    }

    private static String yamlQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void appendLog(String message) {
        File logFile = new File(getFilesDir(), "mediamtx-service.log");
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = timestamp + " " + message + "\n";

        try {
            if (logFile.exists() && logFile.length() > LOG_MAX_BYTES) {
                File rotated = new File(getFilesDir(), "mediamtx-service.log.1");
                if (rotated.exists() && !rotated.delete()) {
                    return;
                }
                if (!logFile.renameTo(rotated)) {
                    return;
                }
            }
            try (FileOutputStream stream = new FileOutputStream(logFile, true)) {
                stream.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    private void startInForeground(String text, int rtspPort) {
        Notification notification = buildNotification(text, rtspPort);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text, int rtspPort) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text, rtspPort));
    }

    private Notification buildNotification(String text, int rtspPort) {
        Intent stopIntent = new Intent(this, MediaMtxService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent restartIntent = new Intent(this, MediaMtxService.class).setAction(ACTION_RESTART);
        restartIntent.putExtra(EXTRA_RTSP_PORT, rtspPort);
        PendingIntent restartPendingIntent = PendingIntent.getService(
                this, 2, restartIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_relay)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(R.drawable.ic_stat_relay, getString(R.string.notification_action_restart), restartPendingIntent)
                .addAction(R.drawable.ic_stat_relay, getString(R.string.notification_action_stop), stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private static final class RelayConfig {
        final int rtspPort;
        final String sourceUrl;
        final boolean tcpOnly;

        RelayConfig(int rtspPort, String sourceUrl, boolean tcpOnly) {
            this.rtspPort = rtspPort;
            this.sourceUrl = sourceUrl;
            this.tcpOnly = tcpOnly;
        }

        static RelayConfig defaults() {
            return new RelayConfig(DEFAULT_RTSP_PORT, null, true);
        }

        boolean sameAs(RelayConfig other) {
            if (other == null) {
                return false;
            }
            if (rtspPort != other.rtspPort || tcpOnly != other.tcpOnly) {
                return false;
            }
            if (sourceUrl == null) {
                return other.sourceUrl == null;
            }
            return sourceUrl.equals(other.sourceUrl);
        }

        static RelayConfig fromIntent(Intent intent) {
            int rtspPort = DEFAULT_RTSP_PORT;
            String sourceUrl = null;
            boolean tcpOnly = true;

            if (intent != null) {
                rtspPort = intent.getIntExtra(EXTRA_RTSP_PORT, DEFAULT_RTSP_PORT);
                sourceUrl = sanitizeSourceUrl(intent.getStringExtra(EXTRA_SOURCE_URL));
                tcpOnly = intent.getBooleanExtra(EXTRA_TCP_ONLY, true);
            }

            if (rtspPort < 1024 || rtspPort > 65535) {
                rtspPort = DEFAULT_RTSP_PORT;
            }

            return new RelayConfig(rtspPort, sourceUrl, tcpOnly);
        }

        private static String sanitizeSourceUrl(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty() || trimmed.contains("\n") || trimmed.contains("\r")) {
                return null;
            }
            return trimmed;
        }
    }
}
