package com.procmonitor.overlay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btn = findViewById(R.id.btn_toggle);
        TextView tv = findViewById(R.id.tv_status);

        btn.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_OVERLAY);
            } else {
                toggleService();
            }
        });

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                toggleService();
            }
            updateStatus();
        }
    }

    private void toggleService() {
        Intent svc = new Intent(this, OverlayService.class);
        if (OverlayService.isRunning) {
            stopService(svc);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        }
        updateStatus();
    }

    private void updateStatus() {
        Button btn = findViewById(R.id.btn_toggle);
        TextView tv = findViewById(R.id.tv_status);
        boolean hasPermission = Settings.canDrawOverlays(this);

        if (!hasPermission) {
            tv.setText("需要悬浮窗权限");
            btn.setText("授予权限");
        } else if (OverlayService.isRunning) {
            tv.setText("悬浮窗运行中");
            btn.setText("关闭悬浮窗");
        } else {
            tv.setText("悬浮窗已关闭");
            btn.setText("开启悬浮窗");
        }
    }
}
