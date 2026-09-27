package com.darren.batterylens;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlertRulesTest {
    @Test public void targetFiresOnceUntilUnplugged() {
        AlertRules r = new AlertRules();
        assertEquals(1, r.evaluate(80, 30, true, 80, 42, 20));
        assertEquals(0, r.evaluate(81, 30, true, 80, 42, 20));
        assertEquals(0, r.evaluate(79, 30, true, 80, 42, 20));
        r.evaluate(79, 30, false, 80, 42, 20);
        assertEquals(1, r.evaluate(80, 30, true, 80, 42, 20));
    }
    @Test public void heatUsesHysteresis() {
        AlertRules r = new AlertRules();
        assertEquals(2, r.evaluate(50, 42, false, 80, 42, 20));
        assertEquals(0, r.evaluate(50, 40, false, 80, 42, 20));
        assertEquals(0, r.evaluate(50, 42, false, 80, 42, 20));
        r.evaluate(50, 38, false, 80, 42, 20);
        assertEquals(2, r.evaluate(50, 42, false, 80, 42, 20));
    }
    @Test public void lowBatteryIsOptionalAndNeverFiresOnCharger() {
        AlertRules r = new AlertRules();
        assertEquals(0, r.evaluate(10, 30, false, 80, 42, 0));
        assertEquals(0, r.evaluate(10, 30, true, 80, 42, 20));
        assertEquals(4, r.evaluate(10, 30, false, 80, 42, 20));
        assertEquals(0, r.evaluate(11, 30, false, 80, 42, 20));
    }
    @Test public void unknownValuesDoNotTriggerAlerts() {
        assertEquals(0, new AlertRules().evaluate(-1, Double.NaN, false, 80, 42, 20));
        assertEquals(0, new AlertRules().evaluate(101, Double.NaN, true, 80, 42, 20));
    }
    @Test public void restoredStateDoesNotRepeatAlert() {
        AlertRules r = new AlertRules(); r.targetSent = r.heatSent = r.lowSent = true;
        assertEquals(0, r.evaluate(90, 45, true, 80, 42, 20));
    }
    @Test public void thresholdsCanTriggerTogether() {
        assertEquals(6, new AlertRules().evaluate(15, 43, false, 80, 42, 20));
    }
}
