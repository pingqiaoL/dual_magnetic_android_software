package com.climbwheel.mk32.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Reads both existing task CSVs and round-aware CSVs without fabricating samples. */
public final class SavedMeasurement {
    public final long startMs;
    public final long endMs;
    public final long[] timestamps;
    public final float[] values;

    private SavedMeasurement(long startMs, long endMs, List<Long> times, List<Float> samples) {
        this.startMs = startMs;
        this.endMs = endMs;
        timestamps = new long[times.size()];
        values = new float[samples.size()];
        for (int i = 0; i < times.size(); i++) {
            timestamps[i] = times.get(i);
            values[i] = samples.get(i);
        }
    }

    public static SavedMeasurement read(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        String header = reader.readLine();
        if (header == null) throw new IOException("文件为空");
        String[] columns = header.replace("\uFEFF", "").split(",", -1);
        int timeColumn = column(columns, "timestamp_ms");
        int valueColumn = column(columns, "thickness_um");
        int eventColumn = column(columns, "event");
        int startColumn = column(columns, "round_start_ms");
        int endColumn = column(columns, "round_end_ms");
        if (timeColumn < 0 || valueColumn < 0) throw new IOException("文件格式不支持");
        List<Long> times = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        long roundStart = 0;
        long roundEnd = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            String[] cells = line.split(",", -1);
            if (cells.length <= Math.max(timeColumn, valueColumn)) continue;
            if (eventColumn >= 0 && (cells.length <= eventColumn
                    || !"DATA".equals(cells[eventColumn].trim()))) continue;
            try {
                long timestamp = Long.parseLong(cells[timeColumn].trim());
                float value = Float.parseFloat(cells[valueColumn].trim());
                if (timestamp <= 0 || Float.isNaN(value) || Float.isInfinite(value)
                        || (!times.isEmpty() && timestamp <= times.get(times.size() - 1))) continue;
                if (times.size() >= 20_000) throw new IOException("数据超过支持的点数");
                times.add(timestamp);
                values.add(value);
                long start = optionalLong(cells, startColumn);
                long end = optionalLong(cells, endColumn);
                if (start > 0 && start <= timestamp && (roundStart == 0 || start < roundStart)) {
                    roundStart = start;
                }
                roundEnd = Math.max(roundEnd, end);
            } catch (NumberFormatException ignored) {
                // Skip damaged rows; retain all readable measurements in their original order.
            }
        }
        if (times.isEmpty()) throw new IOException("没有有效的测厚数据");
        long start = roundStart > 0 ? roundStart : times.get(0);
        long end = Math.max(roundEnd, times.get(times.size() - 1));
        return new SavedMeasurement(start, end, times, values);
    }

    private static int column(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (name.equals(columns[i].trim())) return i;
        }
        return -1;
    }

    private static long optionalLong(String[] cells, int index) {
        if (index < 0 || index >= cells.length) return 0;
        try { return Long.parseLong(cells[index].trim()); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
