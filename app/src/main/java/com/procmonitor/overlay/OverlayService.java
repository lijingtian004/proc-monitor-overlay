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

import org.json.JSONArray;
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

    // 端口（从文件读取，默认 10273）
    private int port = 10273;
    private int pollInterval = 2000; // 2秒
    private long portLastRead = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        handler = new Handler(Looper.getMainLooper());

        // 读取端口
        readPort();

        // 创建通知渠道（安卓8+前台服务必须）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "overlay", "悬浮窗服务", NotificationManager.IMPORTANCE_LOW);
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
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void readPort() {
        try {
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
                        if (v > 0 && v < 65536) {
                            port = v;
                            portLastRead = System.currentTimeMillis();
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void createOverlay() {
        windowManager = getSystemService(WindowManager.class);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(dp(10), dp(8), dp(10), dp(8));

        // 圆角背景
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(220, 20, 24, 34));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.argb(60, 255, 215, 0)); // 金色边框
        rootLayout.setBackground(bg);

        // 标题栏（可拖动区域）
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(0, 0, 0, dp(4));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("▪ 进程监控");
        tvTitle.setTextColor(Color.argb(180, 200, 200, 220));
        tvTitle.setTextSize(10);
        titleBar.addView(tvTitle);

        // 关闭按钮
        TextView tvClose = new TextView(this);
        tvClose.setText(" ✕");
        tvClose.setTextColor(Color.argb(120, 200, 200, 220));
        tvClose.setTextSize(10);
        tvClose.setOnClickListener(v -> stopSelf());
        titleBar.addView(tvClose);

        rootLayout.addView(titleBar);

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(40, 255, 255, 255));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, 0, 0, dp(4));
        rootLayout.addView(divider, divLp);

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

        params = new WindowManager.LayoutParams(
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

    private LinearLayout rootLayout;
    private WindowManager.LayoutParams params;

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
        // 每30秒重新读一次端口文件
        if (System.currentTimeMillis() - portLastRead > 30000) {
            readPort();
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/api/overlay");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);
            conn.getOutputStream().write("".getBytes());

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                handler.post(() -> updateUI(json));
            }
        } catch (Exception e) {
            // 连接失败时显示提示
            handler.post(() -> {
                tvPower.setText("⚡ 连接中...");
                tvPower.setTextColor(Color.parseColor("#ff8a65"));
            });
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void updateUI(JSONObject json) {
        try {
            // CPU
            double cpuTotal = json.optDouble("cpu_total", 0);
            StringBuilder cpuStr = new StringBuilder();
            cpuStr.append(String.format("CPU %.1f%%", cpuTotal));
            JSONArray cores = json.optJSONArray("cpu_cores");
            if (cores != null && cores.length() > 0) {
                cpuStr.append(" [");
                for (int i = 0; i < cores.length(); i++) {
                    if (i > 0) cpuStr.append(" ");
                    cpuStr.append(String.format("%.0f", cores.optDouble(i)));
                }
                cpuStr.append("]");
            }
            tvCpu.setText(cpuStr.toString());
            tvCpu.setTextColor(cpuTotal > 80 ? Color.parseColor("#f87171")
                    : cpuTotal > 50 ? Color.parseColor("#fbbf24") : Color.WHITE);

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
            String powerStr;
            if (powerMw >= 1000) {
                powerStr = String.format("⚡ %.2f W", powerMw / 1000.0);
            } else {
                powerStr = String.format("⚡ %.0f mW", powerMw);
            }
            tvPower.setText(powerStr);
            tvPower.setTextColor(powerMw > 5000 ? Color.parseColor("#f87171")
                    : powerMw > 2000 ? Color.parseColor("#fbbf24")
                    : Color.parseColor("#4ade80"));

            // 温度 + 电量
            int temp = json.optInt("bat_temp", 0);
            int level = json.optInt("bat_level", 0);
            String status = json.optString("bat_status", "");
            String statusCN = status;
            switch (status) {
                case "Charging": statusCN = "充电中"; break;
                case "Discharging": statusCN = "放电中"; break;
                case "Full": statusCN = "已充满"; break;
                case "Not charging": statusCN = "未充电"; break;
            }
            tvTemp.setText(String.format("🌡 %.1f°C  🔋 %d%% %s", temp / 10.0, level, statusCN));
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

            // 更新通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String notifText = String.format("%s | %.1f°C | %d%%",
                        powerMw >= 1000 ? String.format("%.1fW", powerMw/1000) : String.format("%dmW", (int)powerMw),
                        temp / 10.0, level);
                Notification n = new Notification.Builder(this, "overlay")
                        .setContentTitle("进程监控悬浮窗")
                        .setContentText(notifText)
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .build();
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(1, n);
            }

        } catch (Exception ignored) {}
    }

    private int dp(int px) {
        return (int)(px * getResources().getDisplayMetrics().density);
    }
}
