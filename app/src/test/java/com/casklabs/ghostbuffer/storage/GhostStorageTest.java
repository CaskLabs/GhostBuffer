package com.casklabs.ghostbuffer.storage;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.*;

public class GhostStorageTest {

    @Test
    public void testIso8601FormattingAlwaysInUtc() {
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            // Test in GMT+02:00
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+02:00"));
            String isoEpochGmtPlus2 = GhostStorage.formatIso8601(0L);
            assertEquals("1970-01-01T00:00:00.000Z", isoEpochGmtPlus2);

            // Test in GMT-08:00
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-08:00"));
            String isoEpochGmtMinus8 = GhostStorage.formatIso8601(0L);
            assertEquals("1970-01-01T00:00:00.000Z", isoEpochGmtMinus8);

            // Test specific known timestamp: 1700000000000 ms -> 2023-11-14T22:13:20.000Z
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"));
            String isoKnown = GhostStorage.formatIso8601(1700000000000L);
            assertEquals("2023-11-14T22:13:20.000Z", isoKnown);
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }

    @Test
    public void testGenerateSessionId() {
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+09:00"));
            String sessionId = GhostStorage.generateSessionId(1700000000000L);
            // In UTC, 1700000000000 is 2023-11-14 22:13:20
            assertEquals("20231114_221320_000", sessionId);
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }
}
