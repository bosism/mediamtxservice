package com.skydroid.mediamtxrelay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

public final class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;
    private static final String EXTRA_COMMAND = "command";
    private Intent pendingServiceIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingServiceIntent = toServiceIntent(getIntent());

        if (needsNotificationPermission(pendingServiceIntent)) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            return;
        }

        dispatchAndFinish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        dispatchAndFinish();
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

    private boolean needsNotificationPermission(Intent serviceIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        if (MediaMtxService.ACTION_STOP.equals(serviceIntent.getAction())) {
            return false;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }

    private void dispatchAndFinish() {
        String action = pendingServiceIntent.getAction();
        if (MediaMtxService.ACTION_STOP.equals(action)) {
            startService(pendingServiceIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(pendingServiceIntent);
        } else {
            startService(pendingServiceIntent);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }
}
