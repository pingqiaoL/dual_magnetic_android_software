package com.climbwheel.mk32.video;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import com.climbwheel.mk32.BuildConfig;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/** Lifecycle-aware RTSP playback with bounded connection attempts and automatic retry. */
public final class RtspVideoController implements SurfaceHolder.Callback {
    public interface Listener { void onAvailabilityChanged(boolean available); }

    private static final String TAG = "Mk32Video";
    private static final long CONNECT_TIMEOUT_MS = 6_000;
    private static final int RTP_TIMEOUT_MS = 2_000;
    private static final long STALL_TIMEOUT_MS = 400;
    private static final long MAX_FRAME_AGE_MS = 1_000;
    private static final long MAX_BUFFERED_DURATION_MS = 2_000;
    private static final long WATCHDOG_INTERVAL_MS = 100;
    private static final long NETWORK_CHECK_INTERVAL_MS = 500;
    private static final long FLOW_FRESHNESS_MS = 250;
    private static final long DIAGNOSTIC_INTERVAL_MS = 1_000;
    private static final long RETRY_DELAY_MS = 1_000;

    private final Context context;
    private final SurfaceView surfaceView;
    private final Uri streamUri;
    private final Listener listener;
    private final VideoDiagnostics diagnostics;
    private final boolean loopbackTest;
    private final boolean catchUpEnabled;
    private final LivePlaybackCatchUp catchUp = new LivePlaybackCatchUp();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile ExoPlayer player;
    private boolean active;
    private boolean prepared;
    private boolean renderedFirstFrame;
    private boolean released;
    private boolean surfaceReady;
    private boolean networkWaitLogged;
    private volatile long lastFrameAtMs;
    private volatile long frameMetadataCount;
    private volatile long lastPresentationTimeUs = C.TIME_UNSET;
    private volatile long lastPresentationStepUs = C.TIME_UNSET;
    private volatile long lastReleaseLeadUs = C.TIME_UNSET;
    private long connectStartedAtMs;
    private long lastDiagnosticAtMs;
    private long lastNetworkCheckAtMs;
    private String connectedEthernetAddress;
    private long lastBufferedPositionMs = -1;
    private long lastInputAdvanceAtMs;
    private long testInitialPauseMs;
    private int totalDroppedFrames;
    private long totalFrameProcessingOffsetUs;
    private int frameProcessingOffsetCount;
    private DecoderCounters decoderCounters;
    private String decoderName = "unknown";
    private String inputFormat = "unknown";
    private int attempt;

    private final Runnable connectionTimeout;
    private final Runnable stallTimeout;
    private final Runnable latencyWatchdog;
    private final Runnable retry;

    public RtspVideoController(Context context, SurfaceView surfaceView,
                               String streamUrl, Listener listener) {
        this(context, surfaceView, streamUrl, listener, true);
    }

    /** The disabled policy and loopback transport are only available to debug A/B tests. */
    public RtspVideoController(Context context, SurfaceView surfaceView,
                               String streamUrl, Listener listener, boolean enableCatchUp) {
        this.context = context.getApplicationContext();
        this.surfaceView = surfaceView;
        this.streamUri = Uri.parse(streamUrl);
        this.listener = listener;
        loopbackTest = BuildConfig.DEBUG && "127.0.0.1".equals(streamUri.getHost());
        catchUpEnabled = !loopbackTest || enableCatchUp;
        diagnostics = new VideoDiagnostics(this.context);
        connectionTimeout = () -> {
            if (active && player != null && !renderedFirstFrame) {
                Log.w(TAG, "RTSP connection timed out: " + streamUri);
                diagnostics.record("CONNECTION_TIMEOUT", "after_ms="
                        + (SystemClock.elapsedRealtime() - connectStartedAtMs));
                failAndRetry("connection timeout");
            }
        };
        stallTimeout = () -> {
            if (active && player != null) {
                Log.w(TAG, "RTSP stalled; reconnecting at live edge: " + streamUri);
                diagnostics.record("STALL_TIMEOUT", "after_ms="
                        + (SystemClock.elapsedRealtime() - connectStartedAtMs));
                failAndRetry("buffering timeout");
            }
        };
        latencyWatchdog = new Runnable() {
            @Override public void run() {
                if (!active) return;
                long nowMs = SystemClock.elapsedRealtime();
                ExoPlayer current = player;
                if (current != null && nowMs - lastNetworkCheckAtMs >= NETWORK_CHECK_INTERVAL_MS) {
                    lastNetworkCheckAtMs = nowMs;
                    String address = usableEthernetAddress();
                    if (address == null || !address.equals(connectedEthernetAddress)) {
                        diagnostics.record("NETWORK_CHANGED", "previous=" + connectedEthernetAddress
                                + " current=" + address);
                        failAndRetry("ethernet lost or IPv4 changed");
                        current = null;
                    }
                }
                if (current != null && prepared) {
                    long bufferedMs = current.getTotalBufferedDuration();
                    long bufferedPositionMs = current.getBufferedPosition();
                    if (bufferedPositionMs > lastBufferedPositionMs) {
                        lastInputAdvanceAtMs = nowMs;
                    }
                    lastBufferedPositionMs = bufferedPositionMs;
                    long frameAgeMs = lastFrameAtMs == 0 ? 0
                            : nowMs - lastFrameAtMs;
                    long inputAgeMs = nowMs - lastInputAdvanceAtMs;
                    boolean playbackFlowing = renderedFirstFrame && current.isPlaying()
                            && frameAgeMs <= FLOW_FRESHNESS_MS
                            && inputAgeMs <= FLOW_FRESHNESS_MS;
                    applyPlaybackSpeed(current, catchUpEnabled
                            ? catchUp.update(bufferedMs, playbackFlowing, nowMs) : 1f);
                    if (nowMs - lastDiagnosticAtMs >= DIAGNOSTIC_INTERVAL_MS) {
                        lastDiagnosticAtMs = nowMs;
                        recordPlaybackStats(current, bufferedMs, frameAgeMs);
                    }
                    if (bufferedMs > MAX_BUFFERED_DURATION_MS) {
                        Log.w(TAG, "RTSP backlog " + bufferedMs
                                + " ms; reconnecting at live edge");
                        diagnostics.record("BACKLOG", "buffered_ms=" + bufferedMs);
                        failAndRetry("emergency backlog limit");
                    } else if (frameAgeMs > MAX_FRAME_AGE_MS) {
                        Log.w(TAG, "RTSP frame stalled for " + frameAgeMs
                                + " ms; reconnecting at live edge");
                        diagnostics.record("FRAME_STALL", "frame_age_ms=" + frameAgeMs);
                        failAndRetry("video frame timeout");
                    }
                }
                if (active) handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        retry = this::connect;
        surfaceView.getHolder().addCallback(this);
        surfaceView.setVisibility(View.VISIBLE);
    }

    public void start() {
        if (released) return;
        active = true;
        handler.removeCallbacks(latencyWatchdog);
        handler.post(latencyWatchdog);
        if (surfaceReady) connect();
    }

    public void setInitialTestPauseMs(long pauseMs) {
        if (loopbackTest) testInitialPauseMs = Math.max(0, Math.min(1_000, pauseMs));
    }

    public void stop() {
        active = false;
        handler.removeCallbacks(connectionTimeout);
        handler.removeCallbacks(stallTimeout);
        handler.removeCallbacks(latencyWatchdog);
        handler.removeCallbacks(retry);
        releasePlayer();
        setAvailable(false);
        diagnostics.record("STOP", "controller stopped");
    }

    public void release() {
        if (released) return;
        stop();
        released = true;
        surfaceView.getHolder().removeCallback(this);
        diagnostics.close();
    }

    private void connect() {
        handler.removeCallbacks(retry);
        if (!active || !surfaceReady || player != null) return;
        String ethernetAddress = usableEthernetAddress();
        if (ethernetAddress == null) {
            if (!networkWaitLogged) {
                Log.i(TAG, "Waiting for eth0 IPv4 before opening RTSP");
                diagnostics.record("WAIT_NETWORK", "ethernet=" + ethernetAddress());
                networkWaitLogged = true;
            }
            handler.postDelayed(retry, RETRY_DELAY_MS);
            return;
        }
        networkWaitLogged = false;
        prepared = false;
        renderedFirstFrame = false;
        lastFrameAtMs = 0;
        catchUp.reset();
        frameMetadataCount = 0;
        lastPresentationTimeUs = C.TIME_UNSET;
        lastPresentationStepUs = C.TIME_UNSET;
        lastReleaseLeadUs = C.TIME_UNSET;
        connectStartedAtMs = SystemClock.elapsedRealtime();
        connectedEthernetAddress = ethernetAddress;
        lastNetworkCheckAtMs = connectStartedAtMs;
        lastBufferedPositionMs = -1;
        lastInputAdvanceAtMs = connectStartedAtMs;
        lastDiagnosticAtMs = 0;
        totalDroppedFrames = 0;
        totalFrameProcessingOffsetUs = 0;
        frameProcessingOffsetCount = 0;
        decoderCounters = null;
        decoderName = "unknown";
        inputFormat = "unknown";
        attempt++;
        diagnostics.record("OPEN", "attempt=" + attempt + " uri=" + streamUri
                + " ethernet=" + ethernetAddress() + " catch_up=" + catchUpEnabled);
        try {
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    // RTSP receives continuously; actual backlog is drained by catchUp.
                    .setBufferDurationsMs(100, 250, 50, 50)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            ExoPlayer next = new ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .build();
            player = next;
            next.setVolume(0f);
            next.setVideoSurfaceView(surfaceView);
            next.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (next != player) return;
                    if (state == Player.STATE_READY) {
                        prepared = true;
                        attempt = 0;
                        if (renderedFirstFrame) handler.removeCallbacks(connectionTimeout);
                        handler.removeCallbacks(stallTimeout);
                        Log.i(TAG, "RTSP ready with low-latency buffer: " + streamUri);
                        diagnostics.record("READY", "after_ms="
                                + (SystemClock.elapsedRealtime() - connectStartedAtMs)
                                + " buffered_ms=" + next.getTotalBufferedDuration());
                    } else if (state == Player.STATE_BUFFERING) {
                        Log.i(TAG, "RTSP buffering");
                        diagnostics.record("BUFFERING", "prepared=" + prepared
                                + " buffered_ms=" + next.getTotalBufferedDuration());
                        catchUp.reset();
                        applyPlaybackSpeed(next, 1f);
                        if (prepared && renderedFirstFrame) {
                            handler.removeCallbacks(stallTimeout);
                            handler.postDelayed(stallTimeout, STALL_TIMEOUT_MS);
                        }
                    } else if (state == Player.STATE_ENDED) {
                        diagnostics.record("ENDED", "frame_count=" + frameMetadataCount);
                        failAndRetry("stream ended");
                    }
                }

                @Override public void onRenderedFirstFrame() {
                    if (next != player) return;
                    boolean first = !renderedFirstFrame;
                    renderedFirstFrame = true;
                    lastFrameAtMs = SystemClock.elapsedRealtime();
                    handler.removeCallbacks(connectionTimeout);
                    handler.removeCallbacks(stallTimeout);
                    setAvailable(true);
                    Log.i(TAG, "RTSP first frame rendered: " + streamUri);
                    diagnostics.record("FIRST_FRAME", "after_ms="
                            + (SystemClock.elapsedRealtime() - connectStartedAtMs)
                            + " buffered_ms=" + next.getTotalBufferedDuration()
                            + " release_lead_us=" + lastReleaseLeadUs);
                    if (first && loopbackTest && testInitialPauseMs > 0) {
                        long pauseMs = testInitialPauseMs;
                        testInitialPauseMs = 0;
                        diagnostics.record("TEST_PAUSE", "duration_ms=" + pauseMs);
                        next.pause();
                        handler.postDelayed(() -> {
                            if (active && next == player) next.play();
                        }, pauseMs);
                    }
                }

                @Override public void onPlayerError(PlaybackException error) {
                    if (next != player) return;
                    Log.w(TAG, "RTSP playback error code=" + error.errorCode, error);
                    diagnostics.record("PLAYER_ERROR", "code=" + error.errorCode
                            + " message=" + error.getMessage());
                    failAndRetry("player error " + error.errorCode);
                }
            });
            next.addAnalyticsListener(new AnalyticsListener() {
                @Override public void onVideoEnabled(EventTime eventTime, DecoderCounters counters) {
                    if (next != player) return;
                    decoderCounters = counters;
                    diagnostics.record("VIDEO_ENABLED", "counters=" + counters);
                }

                @Override public void onVideoDecoderInitialized(EventTime eventTime,
                                                                 String name,
                                                                 long initializedTimestampMs,
                                                                 long initializationDurationMs) {
                    if (next != player) return;
                    decoderName = name;
                    diagnostics.record("DECODER", "name=" + name
                            + " init_duration_ms=" + initializationDurationMs);
                }

                @Override public void onVideoInputFormatChanged(EventTime eventTime, Format format) {
                    if (next != player) return;
                    inputFormat = format.width + "x" + format.height + " "
                            + format.sampleMimeType + " fps=" + format.frameRate
                            + " bitrate=" + format.bitrate;
                    diagnostics.record("FORMAT", inputFormat);
                }

                @Override public void onDroppedVideoFrames(EventTime eventTime,
                                                           int droppedFrames,
                                                           long elapsedMs) {
                    if (next != player) return;
                    totalDroppedFrames += droppedFrames;
                    diagnostics.record("DROPPED", "count=" + droppedFrames
                            + " total=" + totalDroppedFrames + " period_ms=" + elapsedMs);
                }

                @Override public void onVideoFrameProcessingOffset(EventTime eventTime,
                                                                   long totalProcessingOffsetUs,
                                                                   int frameCount) {
                    if (next != player) return;
                    totalFrameProcessingOffsetUs += totalProcessingOffsetUs;
                    frameProcessingOffsetCount += frameCount;
                }

                @Override public void onVideoCodecError(EventTime eventTime, Exception error) {
                    if (next != player) return;
                    diagnostics.record("CODEC_ERROR", String.valueOf(error));
                }
            });
            next.setVideoFrameMetadataListener((presentationTimeUs, releaseTimeNs,
                                                format, mediaFormat) -> {
                if (next != player) return;
                long previousPresentationTimeUs = lastPresentationTimeUs;
                lastPresentationTimeUs = presentationTimeUs;
                if (previousPresentationTimeUs != C.TIME_UNSET) {
                    lastPresentationStepUs = presentationTimeUs - previousPresentationTimeUs;
                }
                lastReleaseLeadUs = releaseTimeNs == C.TIME_UNSET ? C.TIME_UNSET
                        : (releaseTimeNs - System.nanoTime()) / 1_000L;
                frameMetadataCount++;
                lastFrameAtMs = SystemClock.elapsedRealtime();
            });
            RtspMediaSource source = new RtspMediaSource.Factory()
                    // Prefer low-latency RTP/UDP. Media3 automatically falls back to
                    // interleaved RTP/TCP if UDP receives no data before this timeout.
                    .setTimeoutMs(RTP_TIMEOUT_MS)
                    // ADB reverse tunnels only TCP; production still prefers UDP.
                    .setForceUseRtpTcp(loopbackTest)
                    .setDebugLoggingEnabled(true)
                    .createMediaSource(MediaItem.fromUri(streamUri));
            next.setMediaSource(source);
            next.setPlayWhenReady(true);
            next.prepare();
            handler.postDelayed(connectionTimeout, CONNECT_TIMEOUT_MS);
            Log.i(TAG, "Opening RTSP attempt " + attempt + ": " + streamUri);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to open RTSP: " + streamUri, error);
            diagnostics.record("OPEN_ERROR", String.valueOf(error));
            failAndRetry("open error");
        }
    }

    private void failAndRetry(String reason) {
        handler.removeCallbacks(connectionTimeout);
        handler.removeCallbacks(stallTimeout);
        handler.removeCallbacks(retry);
        diagnostics.record("RECONNECT", "reason=" + reason + " prepared=" + prepared
                + " frame_count=" + frameMetadataCount);
        // Cover the old surface immediately, before a potentially slow decoder release.
        setAvailable(false);
        releasePlayer();
        if (active) handler.postDelayed(retry, RETRY_DELAY_MS);
    }

    private void releasePlayer() {
        ExoPlayer old = player;
        player = null;
        prepared = false;
        renderedFirstFrame = false;
        lastFrameAtMs = 0;
        connectedEthernetAddress = null;
        catchUp.reset();
        if (old != null) {
            diagnostics.record("RELEASE", "buffered_ms=" + old.getTotalBufferedDuration()
                    + " frame_count=" + frameMetadataCount + " decoder=" + decoderName);
            old.clearVideoSurface();
            old.release();
        }
    }

    private void setAvailable(boolean available) {
        listener.onAvailabilityChanged(available);
    }

    private String usableEthernetAddress() {
        if (loopbackTest) return "debug-loopback";
        try {
            NetworkInterface ethernet = NetworkInterface.getByName("eth0");
            if (ethernet == null || !ethernet.isUp()) return null;
            Enumeration<InetAddress> addresses = ethernet.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        } catch (SocketException error) {
            Log.w(TAG, "Unable to inspect eth0: " + error.getMessage());
        }
        return null;
    }

    private void applyPlaybackSpeed(ExoPlayer current, float speed) {
        if (Math.abs(current.getPlaybackParameters().speed - speed) < 0.001f) return;
        diagnostics.record("CATCH_UP", "speed=" + speed
                + " buffered_ms=" + current.getTotalBufferedDuration());
        current.setPlaybackSpeed(speed);
    }

    private String ethernetAddress() {
        try {
            NetworkInterface ethernet = NetworkInterface.getByName("eth0");
            if (ethernet == null) return "missing";
            Enumeration<InetAddress> addresses = ethernet.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
            return ethernet.isUp() ? "up-no-ipv4" : "down";
        } catch (SocketException error) {
            return "error-" + error.getClass().getSimpleName();
        }
    }

    private void recordPlaybackStats(ExoPlayer current, long bufferedMs, long frameAgeMs) {
        DecoderCounters counters = decoderCounters;
        int rendered = -1;
        int skipped = -1;
        int decoderDropped = -1;
        if (counters != null) {
            counters.ensureUpdated();
            rendered = counters.renderedOutputBufferCount;
            skipped = counters.skippedOutputBufferCount;
            decoderDropped = counters.droppedBufferCount;
        }
        long liveOffsetMs = current.isCurrentMediaItemLive()
                ? current.getCurrentLiveOffset() : C.TIME_UNSET;
        long averageProcessingOffsetUs = frameProcessingOffsetCount == 0 ? C.TIME_UNSET
                : totalFrameProcessingOffsetUs / frameProcessingOffsetCount;
        diagnostics.record("STATS", "state=" + stateName(current.getPlaybackState())
                + " playing=" + current.isPlaying()
                + " position_ms=" + current.getCurrentPosition()
                + " buffered_position_ms=" + current.getBufferedPosition()
                + " buffered_ms=" + bufferedMs
                + " live_offset_ms=" + liveOffsetMs
                + " speed=" + current.getPlaybackParameters().speed
                + " frame_age_ms=" + frameAgeMs
                + " frames=" + frameMetadataCount
                + " pts_us=" + lastPresentationTimeUs
                + " pts_step_us=" + lastPresentationStepUs
                + " release_lead_us=" + lastReleaseLeadUs
                + " processing_offset_avg_us=" + averageProcessingOffsetUs
                + " rendered=" + rendered
                + " skipped=" + skipped
                + " dropped=" + decoderDropped
                + " decoder=" + decoderName
                + " format=" + inputFormat);
    }

    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return "UNKNOWN-" + state;
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (active) {
            handler.removeCallbacks(latencyWatchdog);
            handler.post(latencyWatchdog);
            connect();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        handler.removeCallbacks(connectionTimeout);
        handler.removeCallbacks(stallTimeout);
        handler.removeCallbacks(latencyWatchdog);
        handler.removeCallbacks(retry);
        releasePlayer();
        setAvailable(false);
    }
}
