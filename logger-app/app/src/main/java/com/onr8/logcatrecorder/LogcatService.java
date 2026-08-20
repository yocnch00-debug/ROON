package com.onr8.logcatrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogcatService extends Service {
    public static final String ACTION_START = "com.onr8.logcatrecorder.START";
    public static final String ACTION_STOP = "com.onr8.logcatrecorder.STOP";

    private static final String CHANNEL_ID = "on_r8_logcat";
    private static final int NOTIFICATION_ID = 8801;
    private static final long MAX_BYTES = 300L * 1024L * 1024L;

    private SharedPreferences prefs;
    private Process process;
    private Thread readerThread;
    private File currentFile;
    private volatile boolean stopping;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            ensureForeground("기록 종료 중...");
            stopCaptureAndSave();
            return START_NOT_STICKY;
        }

        if (readerThread == null || !readerThread.isAlive()) {
            startCapture();
        } else {
            ensureForeground("전체 logcat 기록 중");
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (!stopping) {
            try { if (process != null) process.destroy(); } catch (Throwable ignored) {}
            releaseWakeLock();
            prefs.edit().putBoolean("recording", false).apply();
        }
        super.onDestroy();
    }

    private synchronized void startCapture() {
        stopping = false;
        prefs.edit().putString("last_error", "").apply();
        ensureForeground("전체 logcat 기록 중");
        acquireWakeLock();

        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ON_Logcat");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("기록 폴더 생성 실패: " + dir);

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentFile = new File(dir, "ON_R8_LOG_" + stamp + ".txt");

            prefs.edit()
                    .putBoolean("recording", true)
                    .putString("current_file", currentFile.getName())
                    .putLong("bytes", 0L)
                    .apply();

            process = new ProcessBuilder("logcat", "-b", "all", "-v", "threadtime", "-T", "1")
                    .redirectErrorStream(true)
                    .start();

            readerThread = new Thread(() -> readLoop(process), "ON-R8-LogcatReader");
            readerThread.start();
        } catch (Throwable t) {
            fail("기록 시작 실패: " + t);
        }
    }

    private void readLoop(Process p) {
        long lastUiUpdate = 0L;
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(currentFile, false), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

            out.write("===== ON R8 LOGCAT RECORDER START =====\n");
            out.write("start=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "\n");
            out.write("device=" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT + "\n");
            out.write("command=logcat -b all -v threadtime -T 1\n");
            out.write("=======================================\n");
            out.flush();

            String line;
            while (!stopping && (line = in.readLine()) != null) {
                out.write(line);
                out.newLine();

                long now = System.currentTimeMillis();
                if (now - lastUiUpdate > 700) {
                    out.flush();
                    long len = currentFile.length();
                    prefs.edit().putLong("bytes", len).apply();
                    lastUiUpdate = now;
                    if (len >= MAX_BYTES) {
                        out.write("\n===== AUTO STOP: 300MB LIMIT =====\n");
                        out.flush();
                        new Thread(this::stopCaptureAndSave, "ON-R8-AutoStop").start();
                        break;
                    }
                }
            }
            out.flush();
        } catch (Throwable t) {
            if (!stopping) prefs.edit().putString("last_error", "logcat 읽기 오류: " + t).apply();
        }
    }

    private synchronized void stopCaptureAndSave() {
        if (stopping) return;
        stopping = true;
        try { if (process != null) process.destroy(); } catch (Throwable ignored) {}
        try {
            if (readerThread != null && readerThread != Thread.currentThread()) readerThread.join(1200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        try {
            if (currentFile != null && currentFile.exists()) {
                String savedPath = exportToDownloads(currentFile);
                prefs.edit()
                        .putString("saved_name", currentFile.getName())
                        .putString("saved_path", savedPath)
                        .putLong("bytes", currentFile.length())
                        .apply();
            }
        } catch (Throwable t) {
            prefs.edit().putString("last_error", "TXT 저장 실패: " + t).apply();
        }

        prefs.edit().putBoolean("recording", false).putString("current_file", "-").apply();
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private String exportToDownloads(File src) throws Exception {
        String relative = Environment.DIRECTORY_DOWNLOADS + "/ON_Logcat";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert 실패");

            try (FileInputStream in = new FileInputStream(src); OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("MediaStore output stream 실패");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return "Download/ON_Logcat/" + src.getName();
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ON_Logcat");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Download 폴더 생성 실패");
        File dst = new File(dir, src.getName());
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dst.getAbsolutePath();
    }

    private void ensureForeground(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("ON R8 Logcat Recorder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true);
        startForeground(NOTIFICATION_ID, b.build());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "ON R8 로그 기록", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("R8 전체 logcat을 TXT로 기록");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ONR8:LogcatRecorder");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
    }

    private void fail(String message) {
        prefs.edit().putBoolean("recording", false).putString("last_error", message).apply();
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }
}
