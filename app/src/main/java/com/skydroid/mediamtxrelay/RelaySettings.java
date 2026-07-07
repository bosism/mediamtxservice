package com.skydroid.mediamtxrelay;

import android.content.Context;
import android.content.SharedPreferences;

final class RelaySettings {
    static final String PREFS_NAME = "relay_settings";
    static final String KEY_RTSP_PORT = "rtsp_port";
    static final String KEY_SOURCE_URL = "source_url";
    static final String KEY_TCP_ONLY = "tcp_only";
    static final String KEY_CONFIG_YAML = "config_yaml";

    static final int DEFAULT_RTSP_PORT = 8554;
    static final boolean DEFAULT_TCP_ONLY = true;
    private static final String DEFAULT_SOURCE_URL = "rtsp://192.168.144.25:8554/main.264";

    private RelaySettings() {
    }

    static int readRtspPort(Context context) {
        return sanitizePort(preferences(context).getInt(KEY_RTSP_PORT, DEFAULT_RTSP_PORT));
    }

    static String readSourceUrl(Context context) {
        return sanitizeSourceUrl(preferences(context).getString(KEY_SOURCE_URL, null));
    }

    static boolean readTcpOnly(Context context) {
        return preferences(context).getBoolean(KEY_TCP_ONLY, DEFAULT_TCP_ONLY);
    }

    static String readConfigYaml(Context context) {
        String yaml = preferences(context).getString(KEY_CONFIG_YAML, null);
        if (yaml == null || yaml.trim().isEmpty()) {
            return defaultConfig(readRtspPort(context), readSourceUrl(context), readTcpOnly(context));
        }
        String sanitizedYaml = sanitizeConfigYaml(yaml);
        if (isLegacyGeneratedConfig(sanitizedYaml)) {
            sanitizedYaml = defaultConfig();
            saveConfigYaml(context, sanitizedYaml);
        }
        return sanitizedYaml;
    }

    static void save(Context context, int rtspPort, String sourceUrl, boolean tcpOnly) {
        String yaml = defaultConfig(rtspPort, sourceUrl, tcpOnly);
        preferences(context)
                .edit()
                .putInt(KEY_RTSP_PORT, sanitizePort(rtspPort))
                .putString(KEY_SOURCE_URL, valueOrEmpty(sanitizeSourceUrl(sourceUrl)))
                .putBoolean(KEY_TCP_ONLY, tcpOnly)
                .putString(KEY_CONFIG_YAML, yaml)
                .apply();
    }

    static void saveConfigYaml(Context context, String yaml) {
        preferences(context)
                .edit()
                .putString(KEY_CONFIG_YAML, sanitizeConfigYaml(yaml))
                .apply();
    }

    static String sanitizeConfigYaml(String yaml) {
        if (yaml == null || yaml.trim().isEmpty()) {
            return defaultConfig(DEFAULT_RTSP_PORT, null, DEFAULT_TCP_ONLY);
        }
        return yaml.trim() + "\n";
    }

    static String defaultConfig() {
        return defaultConfig(DEFAULT_RTSP_PORT, null, DEFAULT_TCP_ONLY);
    }

    static String defaultConfig(int rtspPort, String sourceUrl, boolean tcpOnly) {
        int sanitizedPort = sanitizePort(rtspPort);
        String sanitizedSourceUrl = sanitizeSourceUrl(sourceUrl);
        if (sanitizedSourceUrl == null) {
            sanitizedSourceUrl = DEFAULT_SOURCE_URL;
        }

        StringBuilder builder = new StringBuilder();
        if (sanitizedPort != DEFAULT_RTSP_PORT) {
            builder.append("rtspAddress: :").append(sanitizedPort).append('\n');
        }
        builder.append("paths:\n");
        builder.append("  cam:\n");
        builder.append("    source: ").append(sanitizedSourceUrl).append('\n');
        builder.append("    sourceOnDemand: yes\n");
        builder.append("    rtspTransport: tcp\n");
        return builder.toString();
    }

    static int detectRtspPort(String yaml) {
        if (yaml == null) {
            return DEFAULT_RTSP_PORT;
        }
        String[] lines = yaml.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("rtspAddress:")) {
                continue;
            }
            String value = trimmed.substring("rtspAddress:".length()).trim();
            value = value.replace("\"", "").replace("'", "");
            int colonIndex = value.lastIndexOf(':');
            if (colonIndex >= 0 && colonIndex + 1 < value.length()) {
                value = value.substring(colonIndex + 1);
            }
            try {
                return sanitizePort(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                return DEFAULT_RTSP_PORT;
            }
        }
        return DEFAULT_RTSP_PORT;
    }

    static int sanitizePort(int value) {
        if (value < 1024 || value > 65535) {
            return DEFAULT_RTSP_PORT;
        }
        return value;
    }

    static String sanitizeSourceUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.contains("\n") || trimmed.contains("\r")) {
            return null;
        }
        return trimmed;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isLegacyGeneratedConfig(String yaml) {
        return yaml.contains("logDestinations: [stdout]")
                && yaml.contains("rtmp: false")
                && yaml.contains("hls: false")
                && yaml.contains("webrtc: false")
                && yaml.contains("paths:")
                && yaml.contains("all_others:");
    }
}
