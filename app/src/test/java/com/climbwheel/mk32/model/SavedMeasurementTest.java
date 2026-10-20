package com.climbwheel.mk32.model;

import org.junit.Test;
import java.io.IOException;
import java.io.StringReader;
import static org.junit.Assert.assertEquals;

public final class SavedMeasurementTest {
    @Test public void readsLegacyFilesAndSkipsNonMeasurementRows() throws Exception {
        SavedMeasurement saved = SavedMeasurement.read(new StringReader(
                "timestamp_ms,event,scan,thickness_um\n"
                + "1000,START_ACK,A,999\n"
                + "1500,DATA,A,123.4\n"
                + "2000,MARK_1,A,888\n"
                + "2500,DATA,A,125.6\n"));
        assertEquals(1500, saved.startMs);
        assertEquals(2500, saved.endMs);
        assertEquals(2, saved.values.length);
        assertEquals(123.4f, saved.values[0], .001f);
    }

    @Test public void restoresRoundTimingAndRejectsCorruptOrDuplicateSamples() throws Exception {
        SavedMeasurement saved = SavedMeasurement.read(new StringReader(
                "\uFEFFtimestamp_ms,event,thickness_um,round_start_ms,round_end_ms\n"
                + "1500,DATA,123,1000,5000\n"
                + "1500,DATA,555,1000,5000\n"
                + "1700,DATA,NaN,1000,5000\n"
                + "1800,DATA,Infinity,1000,5000\n"
                + "bad,DATA,999,1000,5000\n"
                + "2000,DATA,124,1000,5000\n"));
        assertEquals(1000, saved.startMs);
        assertEquals(5000, saved.endMs);
        assertEquals(2, saved.values.length);
        assertEquals(124f, saved.values[1], .001f);
    }

    @Test(expected = IOException.class) public void reportsFilesWithoutValidData() throws Exception {
        SavedMeasurement.read(new StringReader("timestamp_ms,event,thickness_um\n1,DATA,NaN\n"));
    }
}
