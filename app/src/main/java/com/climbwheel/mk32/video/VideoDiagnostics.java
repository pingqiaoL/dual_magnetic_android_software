package com.climbwheel.mk32.video;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.climbwheel.mk32.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small persistent event log that survives the USB/ethernet mode switch on the MK32. */
final class VideoDiagnostics {
    private static final String TAG = "Mk32VideoDiag";
    private static final String FILE_NAME = "video_diagnostics.csv";
    private static final String PREVIOUS_FILE_NAME = "video_diagnostics.previous.csv";
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;

    private final File file;
    private final File previousFile;
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
        Thread thread = new Thread(runnable, "Mk32VideoDiagnostics");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardPolicy());

    VideoDiagnostics(Context context) {
        File filesDir = context.getApplicationContext().getFilesDir();
        file = new File(filesDir, FILE_NAME);
        previousFile = new File(filesDir, PREVIOUS_FILE_NAME);
        writer.execute(this::rotateIfNeeded);
        record("SESSION", "version=" + BuildConfig.VERSION_NAME
                + " version_code=" + BuildConfig.VERSION_CODE);
    }

    void record(String event, String details) {
        final long wallTimeMs = System.currentTimeMillis();
        final long elapsedMs = SystemClock.elapsedRealtime();
        final String cleanEvent = clean(event);
        final String cleanDetails = clean(details);
        Log.i(TAG, cleanEvent + " " + cleanDetails);
        writer.execute(() -> append(wallTimeMs, elapsedMs, cleanEvent, cleanDetails));
    }

    void close() {
        writer.shutdown();
    }

    private void rotateIfNeeded() {
        if (!file.exists() || file.length() < MAX_FILE_BYTES) return;
        if (previousFile.exists() && !previousFile.delete()) {
            Log.w(TAG, "Unable to remove previous diagnostics file");
        }
        if (!file.renameTo(previousFile)) {
            Log.w(TAG, "Unable to rotate diagnostics file");
        }
    }

    private void append(long wallTimeMs, long elapsedMs, String event, String details) {
        rotateIfNeeded();
        boolean writeHeader = !file.exists() || file.length() == 0;
        try (OutputStreamWriter output = new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            if (writeHeader) output.write("wall_time_ms,elapsed_realtime_ms,event,details\n");
            output.write(wallTimeMs + "," + elapsedMs + "," + event + "," + details + "\n");
        } catch (IOException error) {
            Log.w(TAG, "Unable to write video diagnostics", error);
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }
}
