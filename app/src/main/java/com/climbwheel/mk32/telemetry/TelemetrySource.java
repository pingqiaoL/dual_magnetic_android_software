package com.climbwheel.mk32.telemetry;

import com.climbwheel.mk32.model.TelemetrySnapshot;

public interface TelemetrySource {
    enum MeasurementCommand {
        START(31010, "开始测量"),
        STOP(31011, "停止测量"),
        ZERO(31012, "校零");

        public final int mavCommand;
        public final String displayName;
        MeasurementCommand(int mavCommand, String displayName) {
            this.mavCommand = mavCommand;
            this.displayName = displayName;
        }
    }

    interface CommandCallback {
        void onResult(MeasurementCommand command, boolean accepted, String detail);
    }

    interface Listener {
        void onTelemetry(TelemetrySnapshot snapshot);
        void onError(String message, Throwable error);
    }

    void start(Listener listener);
    void stop();
    String description();
    default boolean supportsCommands() { return false; }
    default void sendCommand(MeasurementCommand command, CommandCallback callback) {
        callback.onResult(command, false, "当前数据源不支持双向控制");
    }
}
