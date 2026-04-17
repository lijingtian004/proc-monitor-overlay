package com.procmonitor.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class OverlayService extends Service {
    static boolean isRunning = false;

    private WindowManager windowManager;
    private View overlayView;
    private Handler handler;
    private Runnable poller;

    // 数据显示控件
    private TextView tvCpu, tvGpu, tvPower, tvTemp, tvApp;
    private LinearLayout rootLayout;

    // 端口（从文件读取，默认 10273）
    private int port = 10273;
    private int pollInterval = 1000; // 1秒

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        handler = new Handler(Looper.getMainLooper());

        // 读取端口
        readPort();

        // 创建通知渠道（安卓8+前台服务必须）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("overlay", "悬浮窗服务", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);

            Notification n = new Notification.Builder(this, "overlay")
                    .setContentTitle("进程监控悬浮窗")
                    .setContentText("运行中...")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .build();
            startForeground(1, n);
        }

        createOverlay();
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacks(poller);
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
        super.onDestroy();
    }

    private void readPort() {
        try {
            // 尝试从常见路径读取端口
            String[] paths = {
                "/data/local/tmp/skroot_webui_port",
                "/sdcard/skroot_webui_port"
            };
            for (String p : paths) {
                java.io.File f = new java.io.File(p);
                if (f.exists()) {
                    BufferedReader br = new BufferedReader(new java.io.FileReader(f));
                    String s = br.readLine();
                    br.close();
                    if (s != null) {
                        int v = Integer.parseInt(s.trim());
                        if (v > 0 && v < 65536) { port = v; return; }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void createOverlay() {
        windowManager = getSystemService(WindowManager.class);

        // 创建布局
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(dp(10), dp(8), dp(10), dp(8));
        rootLayout.setBackgroundColor(Color.argb(220, 20, 24, 34)); // 深色半透明

        // 圆角背景
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(220, 20, 24, 34));
        bg.setCornerRadius(dp(12));
        rootLayout.setBackground(bg);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("进程监控");
        tvTitle.setTextColor(Color.argb(180, 200, 200, 220));
        tvTitle.setTextSize(10);
        tvTitle.setGravity(Gravity.CENTER);
        rootLayout.addView(tvTitle);

        // 数据行
        tvCpu = createDataRow();
        tvGpu = createDataRow();
        tvPower = createDataRow();
        tvTemp = createDataRow();
        tvApp = createDataRow();

        rootLayout.addView(tvCpu);
        rootLayout.addView(tvGpu);
        rootLayout.addView(tvPower);
        rootLayout.addView(tvTemp);
        rootLayout.addView(tvApp);

        // 窗口参数
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(8);
        params.y = dp(80);

        // 拖动支持
        rootLayout.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX - (int)(event.getRawX() - initialTouchX);
                        params.y = initialY + (int)(event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(rootLayout, params);
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(rootLayout, params);
    }

    private TextView createDataRow() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(0, 1.2f);
        tv.setPadding(0, dp(2), 0, 0);
        return tv;
    }

    private void startPolling() {
        poller = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> fetchAndDisplay()).start();
                handler.postDelayed(this, pollInterval);
            }
        };
        handler.post(poller);
    }

    private void fetchAndDisplay() {
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/api/overlay");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                handler.post(() -> updateUI(json));
            }
        } catch (Exception ignored) {}
    }

    private void updateUI(JSONObject json) {
        try {
            // CPU
            double cpuTotal = json.optDouble("cpu_total", 0);
            StringBuilder cpuStr = new StringBuilder();
            cpuStr.append(String.format("CPU %.1f%%", cpuTotal));
            org.json.JSONArray cores = json.optJSONArray("cpu_cores");
            if (cores != null && cores.length() > 0) {
                cpuStr.append(" [");
                for (int i = 0; i < cores.length(); i++) {
                    if (i > 0) cpuStr.append(" ");
                    cpuStr.append(String.format("%.0f", cores.optDouble(i)));
                }
                cpuStr.append("]");
            }
            tvCpu.setText(cpuStr.toString());
            tvCpu.setTextColor(cpuTotal > 80 ? Color.parseColor("#f87171") : Color.WHITE);

            // GPU
            double gpuPct = json.optDouble("gpu_pct", -1);
            if (gpuPct >= 0) {
                tvGpu.setText(String.format("GPU %.1f%% %s", gpuPct, json.optString("gpu_name", "")));
                tvGpu.setVisibility(View.VISIBLE);
            } else {
                tvGpu.setVisibility(View.GONE);
            }

            // 功耗
            double powerMw = json.optDouble("power_mw", 0);
            String powerStr = powerMw >= 1000
                    ? String.format("⚡ %.2f W", powerMw / 1000)
                    : String.format("⚡ %.0f mW", powerMw);
            tvPower.setText(powerStr);
            tvPower.setTextColor(powerMw > 3000 ? Color.parseColor("#f87171")
                    : powerMw > 1500 ? Color.parseColor("#fbbf24") : Color.parseColor("#4ade80"));

            // 温度
            int temp = json.optInt("bat_temp", 0);
            int level = json.optInt("bat_level", 0);
            tvTemp.setText(String.format("🌡 %.1f°C  🔋 %d%%", temp / 10.0, level));
            tvTemp.setTextColor(temp > 400 ? Color.parseColor("#f87171") : Color.WHITE);

            // 前台应用
            String fgApp = json.optString("fg_app", "");
            double fgCpu = json.optDouble("fg_cpu", 0);
            int fgMem = json.optInt("fg_mem", 0);
            if (!fgApp.isEmpty()) {
                String shortName = fgApp.contains(".") ? fgApp.substring(fgApp.lastIndexOf('.') + 1) : fgApp;
                tvApp.setText(String.format("📱 %s  CPU %.1f%%  %dM", shortName, fgCpu, fgMem));
                tvApp.setVisibility(View.VISIBLE);
            } else {
                tvApp.setVisibility(View.GONE);
            }

        } catch (Exception ignored) {}
    }

    private int dp(int px) {
        return (int)(px * getResources().getDisplayMetrics().density);
    }
}
