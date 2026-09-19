package com.darren.batterylens;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CalibrationSessionTest {
    @Test public void missingTemperatureDoesNotTruncateSavedSession() {
        CalibrationStore.Session session = new CalibrationStore.Session();
        session.startTime = 1000; session.endTime = 3601000;
        session.startSoc = 20; session.endSoc = 80;
        session.startUah = 913000; session.endUah = 3652000;
        session.startTemp = Double.NaN; session.endTemp = Double.NaN;
        session.fccMah = 4565; session.source = "manual";
        session.quality = "History only"; session.reason = "Temperature unavailable";
        session.samples = 61; session.used = false;
        JSONObject saved = session.toJson();
        CalibrationStore.Session restored = CalibrationStore.Session.fromJson(saved);
        assertEquals("manual", saved.optString("src"));
        assertEquals(4565, restored.fccMah, .001);
        assertEquals(61, restored.samples);
        assertTrue(Double.isNaN(restored.startTemp));
        assertEquals("Temperature unavailable", restored.reason);
        assertFalse(restored.used);
    }
    @Test public void invalidCapacityStillKeepsRejectionAndSource() {
        CalibrationStore.Session session = new CalibrationStore.Session();
        session.fccMah = Double.NaN;
        session.source = "auto"; session.reason = "Invalid charge counter";
        session.quality = "History only";
        CalibrationStore.Session restored = CalibrationStore.Session.fromJson(session.toJson());
        assertTrue(Double.isNaN(restored.fccMah));
        assertEquals("auto", restored.source);
        assertEquals("Invalid charge counter", restored.reason);
    }
    @Test public void oldUnvalidatedHistoryIsRetainedWithoutBecomingEligible() throws Exception {
        CalibrationStore.Session restored = CalibrationStore.Session.fromJson(
                new JSONObject("{\"fcc\":4565,\"used\":true,\"src\":\"auto\"}"));
        assertEquals(4565, restored.fccMah, .001);
        assertFalse(restored.used);
        assertTrue(restored.reason.contains("Imported"));
    }
    @Test public void validNewSessionRoundTrips() {
        CalibrationStore.Session s = new CalibrationStore.Session();
        s.fccMah = 4565; s.minTemp = 25; s.maxTemp = 35; s.samples = 61;
        s.source = "auto"; s.used = true; s.reason = "Charger disconnected";
        CalibrationStore.Session restored = CalibrationStore.Session.fromJson(s.toJson());
        assertTrue(restored.used);
        assertEquals(25, restored.minTemp, .01);
        assertEquals(35, restored.maxTemp, .01);
    }
}
