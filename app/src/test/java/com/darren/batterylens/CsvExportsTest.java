package com.darren.batterylens;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class CsvExportsTest {
    @Test public void historicalMissingSensorsDoNotBecomeZeros() {
        String csv = CsvExports.trends(Collections.singletonList(
                new TrendPoint(1234, 123, 1, -1, Double.NaN, Double.NaN, false)));
        assertTrue(csv.startsWith("time_utc_ms,"));
        assertTrue(csv.contains("\n1234,123,1,,,,false,,false\n"));
    }

    @Test public void rejectedSessionReasonIsEscapedAndRetained() {
        CalibrationStore.Session session = new CalibrationStore.Session();
        session.source = "auto";
        session.reason = "Counter \"paused\", review";
        session.fccMah = Double.NaN;
        String csv = CsvExports.sessions(Collections.singletonList(session));
        assertTrue(csv.contains("\"Counter \"\"paused\"\", review\""));
        assertTrue(csv.contains("start_voltage_mv,end_voltage_mv"));
    }
}
