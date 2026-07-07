package com.skydroid.mediamtxrelay;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;
    private static final String EXTRA_COMMAND = "command";
    private static final int LOG_PREVIEW_CHARS = 12_000;

    private Intent pendingServiceIntent;
    private boolean finishAfterDispatch;
    private EditText yamlInput;
    private TextView statusText;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isHeadlessCommand(getIntent())) {
            handleHeadlessCommand(getIntent());
            return;
        }

        buildSettingsUi();
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isHeadlessCommand(intent)) {
            handleHeadlessCommand(intent);
            return;
        }
        if (yamlInput != null) {
            yamlInput.setText(RelaySettings.readConfigYaml(this));
        }
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        dispatchPendingServiceIntent();
    }

    private void handleHeadlessCommand(Intent intent) {
        pendingServiceIntent = toServiceIntent(intent);
        finishAfterDispatch = true;
        dispatchOrRequestPermission();
    }

    private Intent toServiceIntent(Intent source) {
        String action = source.getAction();
        if (action == null || Intent.ACTION_MAIN.equals(action)) {
            action = MediaMtxService.ACTION_START;
        }

        String command = source.getStringExtra(EXTRA_COMMAND);
        if (command != null) {
            if ("stop".equalsIgnoreCase(command)) {
                action = MediaMtxService.ACTION_STOP;
            } else if ("restart".equalsIgnoreCase(command)) {
                action = MediaMtxService.ACTION_RESTART;
            } else if ("start".equalsIgnoreCase(command)) {
                action = MediaMtxService.ACTION_START;
            }
        }

        Intent serviceIntent = new Intent(this, MediaMtxService.class);
        serviceIntent.setAction(action);
        if (source.getExtras() != null) {
            serviceIntent.putExtras(source.getExtras());
        }
        return serviceIntent;
    }

    private boolean isHeadlessCommand(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        if (action != null && !Intent.ACTION_MAIN.equals(action)) {
            return true;
        }
        Bundle extras = intent.getExtras();
        return extras != null && (
                extras.containsKey(EXTRA_COMMAND)
                        || extras.containsKey(MediaMtxService.EXTRA_RTSP_PORT)
                        || extras.containsKey(MediaMtxService.EXTRA_SOURCE_URL)
                        || extras.containsKey(MediaMtxService.EXTRA_TCP_ONLY)
                        || extras.containsKey(MediaMtxService.EXTRA_CONFIG_YAML));
    }

    private boolean needsNotificationPermission(Intent serviceIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        if (MediaMtxService.ACTION_STOP.equals(serviceIntent.getAction())) {
            return false;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }

    private void dispatchOrRequestPermission() {
        if (needsNotificationPermission(pendingServiceIntent)) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            return;
        }
        dispatchPendingServiceIntent();
    }

    private void dispatchPendingServiceIntent() {
        if (pendingServiceIntent == null) {
            return;
        }

        String action = pendingServiceIntent.getAction();
        if (MediaMtxService.ACTION_STOP.equals(action)) {
            startService(pendingServiceIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(pendingServiceIntent);
        } else {
            startService(pendingServiceIntent);
        }

        pendingServiceIntent = null;
        if (finishAfterDispatch) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        } else {
            refreshStatus();
        }
    }

    private void buildSettingsUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        scrollView.addView(root);

        TextView title = textView("MediaMTX Relay", 24, true);
        root.addView(title, matchWrap());

        statusText = textView("", 15, false);
        statusText.setPadding(0, dp(6), 0, dp(16));
        root.addView(statusText, matchWrap());

        LinearLayout commandRow = horizontalRow();
        Button startButton = button("Start / Restart");
        startButton.setOnClickListener(view -> startFromUi());
        Button stopButton = button("Stop");
        stopButton.setOnClickListener(view -> stopFromUi());
        commandRow.addView(startButton, weightedWrap());
        commandRow.addView(stopButton, weightedWrap());
        root.addView(commandRow, matchWrap());

        root.addView(label("mediamtx.yml"), matchWrap());
        yamlInput = new EditText(this);
        yamlInput.setGravity(Gravity.TOP | Gravity.START);
        yamlInput.setHorizontallyScrolling(false);
        yamlInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        yamlInput.setMinLines(12);
        yamlInput.setText(RelaySettings.readConfigYaml(this));
        yamlInput.setTextSize(12);
        yamlInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(yamlInput, matchWrap());

        TextView logLabel = label("Log");
        logLabel.setPadding(0, dp(18), 0, dp(4));
        root.addView(logLabel, matchWrap());

        logText = textView("", 12, false);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        root.addView(logText, matchWrap());

        setContentView(scrollView);
    }

    private void startFromUi() {
        String yaml = saveYaml();

        Intent intent = new Intent(this, MediaMtxService.class)
                .setAction(MediaMtxService.ACTION_START)
                .putExtra(MediaMtxService.EXTRA_CONFIG_YAML, yaml);

        pendingServiceIntent = intent;
        finishAfterDispatch = false;
        dispatchOrRequestPermission();
    }

    private void stopFromUi() {
        pendingServiceIntent = new Intent(this, MediaMtxService.class).setAction(MediaMtxService.ACTION_STOP);
        finishAfterDispatch = false;
        dispatchPendingServiceIntent();
    }

    private String saveYaml() {
        String yaml = RelaySettings.sanitizeConfigYaml(yamlInput.getText().toString());
        yamlInput.setText(yaml);
        RelaySettings.saveConfigYaml(this, yaml);
        refreshStatus();
        return yaml;
    }

    private void refreshStatus() {
        if (statusText == null || logText == null) {
            return;
        }
        int port = RelaySettings.detectRtspPort(RelaySettings.readConfigYaml(this));
        statusText.setText(isServiceRunning()
                ? "Running on rtsp://<device-ip>:" + port
                : "Stopped");
        logText.setText(readLogPreview());
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
        for (ActivityManager.RunningServiceInfo service : services) {
            if (MediaMtxService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private String readLogPreview() {
        File logFile = new File(getFilesDir(), "mediamtx-service.log");
        if (!logFile.exists()) {
            return "";
        }

        try {
            byte[] bytes = Files.readAllBytes(logFile.toPath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.length() <= LOG_PREVIEW_CHARS) {
                return text;
            }
            return text.substring(text.length() - LOG_PREVIEW_CHARS);
        } catch (IOException e) {
            return "Unable to read log: " + e.getMessage();
        }
    }

    private TextView label(String value) {
        TextView label = textView(value, 13, true);
        label.setPadding(0, dp(14), 0, 0);
        return label;
    }

    private TextView textView(String value, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        if (bold) {
            textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return textView;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMarginEnd(dp(8));
        return params;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
