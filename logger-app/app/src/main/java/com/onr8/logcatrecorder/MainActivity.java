package com.onr8.logcatrecorder;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String READ_LOGS = "android.permission.READ_LOGS";
    private static final String PKG = "com.onr8.logcatrecorder";
    private static final String ADB_CMD = "adb shell pm grant " + PKG + " android.permission.READ_LOGS";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView detailView;
    private TextView adbView;
    private Button startButton;
    private Button stopButton;
    private SharedPreferences prefs;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refreshUi();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        buildUi();

        if (hasReadLogsPermission()) {
            // User asked for ADB-like behavior: opening the app starts a new capture automatically.
            startRecorder();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("ON R8 Logcat Recorder");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(30, 41, 59));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(18));
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(18);
        statusView.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(statusView, matchWrap());

        detailView = new TextView(this);
        detailView.setTextSize(14);
        detailView.setTextColor(Color.DKGRAY);
        detailView.setTextIsSelectable(true);
        detailView.setPadding(0, dp(12), 0, dp(16));
        root.addView(detailView, matchWrap());

        startButton = new Button(this);
        startButton.setText("기록 시작");
        startButton.setOnClickListener(v -> startRecorder());
        root.addView(startButton, matchWrap());

        stopButton = new Button(this);
        stopButton.setText("기록 종료 + TXT 저장");
        stopButton.setOnClickListener(v -> stopRecorder());
        root.addView(stopButton, matchWrap());

        TextView divider = new TextView(this);
        divider.setText("\n최초 1회 ADB 권한\n");
        divider.setTextSize(16);
        divider.setTextColor(Color.rgb(51, 65, 85));
        root.addView(divider, matchWrap());

        adbView = new TextView(this);
        adbView.setText(ADB_CMD);
        adbView.setTextSize(14);
        adbView.setTextColor(Color.rgb(30, 64, 175));
        adbView.setTextIsSelectable(true);
        adbView.setPadding(dp(12), dp(12), dp(12), dp(12));
        adbView.setBackgroundColor(Color.rgb(239, 246, 255));
        root.addView(adbView, matchWrap());

        Button copy = new Button(this);
        copy.setText("ADB 명령 복사");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ADB", ADB_CMD));
            Toast.makeText(this, "ADB 명령 복사됨", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy, matchWrap());

        TextView note = new TextView(this);
        note.setText("사용법: 앱을 열면 자동 기록 → HiBy Music/Roon Ready/Tamra 테스트 → 이 앱으로 돌아와 '기록 종료 + TXT 저장'.\n저장 위치: Download/ON_Logcat/");
        note.setTextSize(14);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, matchWrap());

        setContentView(scroll);
    }

    private void startRecorder() {
        if (!hasReadLogsPermission()) {
            Toast.makeText(this, "먼저 ADB로 READ_LOGS 권한을 1회 부여해줘", Toast.LENGTH_LONG).show();
            refreshUi();
            return;
        }
        Intent i = new Intent(this, LogcatService.class);
        i.setAction(LogcatService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        handler.postDelayed(this::refreshUi, 300);
    }

    private void stopRecorder() {
        Intent i = new Intent(this, LogcatService.class);
        i.setAction(LogcatService.ACTION_STOP);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        handler.postDelayed(this::refreshUi, 500);
    }

    private boolean hasReadLogsPermission() {
        return checkSelfPermission(READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshUi() {
        boolean permission = hasReadLogsPermission();
        boolean recording = prefs.getBoolean("recording", false);
        String current = prefs.getString("current_file", "-");
        String saved = prefs.getString("saved_name", "-");
        String savedPath = prefs.getString("saved_path", "-");
        String error = prefs.getString("last_error", "");
        long bytes = prefs.getLong("bytes", 0L);

        if (!permission) {
            statusView.setText("● ADB 권한 필요");
            statusView.setTextColor(Color.rgb(185, 28, 28));
            statusView.setBackgroundColor(Color.rgb(254, 226, 226));
        } else if (recording) {
            statusView.setText("● 전체 로그 기록 중");
            statusView.setTextColor(Color.rgb(21, 128, 61));
            statusView.setBackgroundColor(Color.rgb(220, 252, 231));
        } else {
            statusView.setText("● 기록 대기");
            statusView.setTextColor(Color.rgb(71, 85, 105));
            statusView.setBackgroundColor(Color.rgb(241, 245, 249));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("READ_LOGS: ").append(permission ? "허용됨" : "미허용").append('\n');
        sb.append("현재 기록: ").append(current).append('\n');
        sb.append(String.format(Locale.US, "현재 크기: %.2f MB\n", bytes / 1048576.0));
        sb.append("마지막 저장: ").append(saved).append('\n');
        sb.append("저장 위치: ").append(savedPath);
        if (!error.isEmpty()) sb.append("\n\n마지막 오류: ").append(error);
        detailView.setText(sb.toString());

        startButton.setEnabled(permission && !recording);
        stopButton.setEnabled(recording);
        adbView.setAlpha(permission ? 0.55f : 1.0f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
