package com.bosism.mediamtxrelay;

import android.content.Context;
import android.content.SharedPreferences;

final class RelaySettings {
    static final String PREFS_NAME = "relay_settings";
    static final String KEY_YAML = "yaml";
    static final String KEY_RUNNING = "running";

    private static final String DEFAULT_SOURCE_URL = "rtsp://192.168.144.25:8554/main.264";

    private RelaySettings() {
    }

    static String readConfigYaml(Context context) {
        String yaml = preferences(context).getString(KEY_YAML, null);
        if (yaml == null || yaml.trim().isEmpty()) {
            return defaultConfig();
        }
        return sanitizeConfigYaml(yaml);
    }

    static void saveConfigYaml(Context context, String yaml) {
        preferences(context)
                .edit()
                .putString(KEY_YAML, sanitizeConfigYaml(yaml))
                .apply();
    }

    static String sanitizeConfigYaml(String yaml) {
        if (yaml == null || yaml.trim().isEmpty()) {
            return defaultConfig();
        }
        return yaml.trim() + "\n";
    }

    static String defaultConfig() {
        return "paths:\n"
                + "  cam:\n"
                + "    source: " + DEFAULT_SOURCE_URL + "\n"
                + "    sourceOnDemand: yes\n"
                + "    rtspTransport: tcp\n";
    }

    static boolean isRunning(Context context) {
        return preferences(context).getBoolean(KEY_RUNNING, false);
    }

    static void setRunning(Context context, boolean running) {
        preferences(context)
                .edit()
                .putBoolean(KEY_RUNNING, running)
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

}
