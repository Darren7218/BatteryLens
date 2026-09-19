package com.darren.batterylens;

/** Executable regression cases; run with tools/test-core.sh (JDK 17 only). */
public final class MeasurementRulesTest {
    private static int cases;
    private static void check(boolean ok, String name) {
        cases++;
        if (!ok) throw new AssertionError(name);
    }
    private static void near(double expected, double actual, String name) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < .00001, name);
    }
    public static void main(String[] args) {
        double fcc = MeasurementRules.capacityMah(20, 80, 913000, 3652000);
        near(4565, fcc, "S24 FE 20–80% unit conversion");
        check(MeasurementRules.rejection(fcc, 60, 60 * 60000, 25, 35, 4565, 61).isEmpty(), "normal charge accepted");
        check(!MeasurementRules.rejection(fcc, 19, 60 * 60000, 25, 35, 4565, 20).isEmpty(), "short charge excluded");
        check(!MeasurementRules.rejection(fcc, 60, 60000, 25, 35, 4565, 20).isEmpty(), "instant SOC jump excluded");
        check(!MeasurementRules.rejection(fcc, 60, 60 * 60000, 25, 46, 4565, 20).isEmpty(), "hot session excluded");
        check(!MeasurementRules.rejection(fcc, 60, 60 * 60000, 5, 35, 4565, 20).isEmpty(), "cold session excluded");
        check(!MeasurementRules.rejection(fcc, 60, 60 * 60000, Double.NaN, 35, 4565, 20).isEmpty(), "missing temperature cannot look calibrated");
        check(!MeasurementRules.rejection(fcc, 60, 60 * 60000, 25, 35, 4565, 2).isEmpty(), "two endpoints are insufficient");
        check(!MeasurementRules.rejection(8000, 60, 60 * 60000, 25, 35, 4565, 20).isEmpty(), "implausible device-relative capacity excluded");
        check(Double.isNaN(MeasurementRules.capacityMah(20, 80, Integer.MIN_VALUE, 3652000)), "unsupported sentinel excluded");
        check(Double.isNaN(MeasurementRules.capacityMah(20, 80, 913000, 500000)), "counter reset excluded");
        check(Double.isNaN(MeasurementRules.capacityMah(80, 80, 913000, 3652000)), "no percent change excluded");
        check(Double.isNaN(MeasurementRules.capacityMah(20, 101, 913000, 3652000)), "invalid percentage excluded");
        check(Double.isNaN(MeasurementRules.capacityMah(Double.NaN, 80, 913000, 3652000)), "NaN percentage excluded");
        check(MeasurementRules.continuous(1000, 61000, 7, 7), "minute checkpoint continuous");
        check(!MeasurementRules.continuous(1000, 900000, 7, 7), "missed monitoring segment cannot be bridged");
        check(!MeasurementRules.continuous(1000, 61000, 7, 8), "reboot breaks continuity even if uptime increases");
        check(!MeasurementRules.continuous(100000, 1000, -1, -1), "uptime reset breaks continuity when boot count absent");
        near(.01, MeasurementRules.observedDischarge(80, 79, false, false, true), "one percent discharge = .01 observed cycle");
        near(0, MeasurementRules.observedDischarge(80, 79, true, false, true), "unplug boundary excluded from cycle estimate");
        near(0, MeasurementRules.observedDischarge(80, 20, false, false, true), "fuel-gauge jump excluded from cycles");
        near(0, MeasurementRules.observedDischarge(80, 79, false, false, false), "unmonitored time excluded");
        near(0, MeasurementRules.observedDischarge(79, 80, false, false, true), "charging never increases discharge cycles");
        near(0, MeasurementRules.observedDischarge(80, 79, true, true, true), "plugged fluctuations excluded");
        double total = 0;
        for (int i = 100; i > 0; i--) total += MeasurementRules.observedDischarge(i, i-1, false, false, true);
        near(1, total, "100 observed percent equals one full equivalent cycle");
        check(Integer.valueOf(234).equals(MeasurementRules.cycleValue("234\n")), "readable cycle count parsed");
        check(Integer.valueOf(0).equals(MeasurementRules.cycleValue("0")), "reported zero preserved");
        for (String raw : new String[]{"-1", "unknown", "1.5", "2147483647", "100001", "123 cycles", "", "123\n456"})
            check(MeasurementRules.cycleValue(raw) == null, "invalid cycle counter: " + raw);
        check(!MeasurementRules.rejection(Double.NaN, 60, 3600000, 25, 35, 4565, 61).isEmpty(), "NaN capacity rejected");
        check(!MeasurementRules.rejection(fcc, 60, 19 * 3600000L, 25, 35, 4565, 61).isEmpty(), "stale charge excluded");
        System.out.println("PASS: " + cases + " measurement regression cases");
    }
}
