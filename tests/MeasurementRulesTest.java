package com.darren.batterylens;

import java.util.ArrayList;
import java.util.List;

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
        check(!MeasurementRules.rejection(fcc, 60, 49 * 3600000L, 25, 35, 4565, 61).isEmpty(), "stale charge excluded");
        List<MeasurementRules.ChargePoint> slowCharge = new ArrayList<>();
        for (int soc = 20; soc <= 90; soc += 2)
            slowCharge.add(new MeasurementRules.ChargePoint(soc, 913000 + (soc - 20) * 45650L));
        MeasurementRules.CapacityFit steady = MeasurementRules.fitCapacity(slowCharge);
        check(steady != null && steady.pairs >= 6, "many SOC levels support a checked estimate");
        near(4565, steady.mah, "slow 20-to-90 charging has the same capacity independent of elapsed time");
        check(MeasurementRules.fitRejection(steady, 0).isEmpty(), "steady cap-limited session accepted");
        // A paused phone can sit at 90% for hours with no further capacity data.
        check(!MeasurementRules.forwardProgress(90, 4108500, 90, 4208500), "plateau cannot move the calibrated endpoint");
        check(!MeasurementRules.forwardProgress(90, 4108500, 89, 4050000), "hotspot discharge cannot move the endpoint backwards");
        check(MeasurementRules.forwardProgress(90, 4108500, 91, 4150000), "normal progress resumes after a dip");
        // One bad first or last counter read should not dominate the full-charge estimate.
        slowCharge.set(slowCharge.size() - 1, new MeasurementRules.ChargePoint(90, 3708500));
        MeasurementRules.CapacityFit noisyEndpoint = MeasurementRules.fitCapacity(slowCharge);
        check(noisyEndpoint != null, "still enough valid comparisons after a bad endpoint");
        check(Math.abs(noisyEndpoint.mah - 4565) < 100, "multi-point fit resists one false-low endpoint");
        slowCharge.clear();
        for (int soc = 20; soc <= 90; soc += 2)
            slowCharge.add(new MeasurementRules.ChargePoint(soc, 913000 + (soc - 20) *
                    (soc < 56 ? 25000L : 65000L)));
        MeasurementRules.CapacityFit inconsistent = MeasurementRules.fitCapacity(slowCharge);
        check(inconsistent != null && !MeasurementRules.fitRejection(inconsistent, 0).isEmpty(),
                "severely inconsistent counter behavior cannot silently lower health");
        check(!MeasurementRules.fitRejection(steady, 3).isEmpty(), "repeated hotspot-load reversals require a new session");
        check(MeasurementRules.fitCapacity(slowCharge.subList(0, 3)) == null,
                "three sparse levels cannot claim a full-capacity result");
        for (int limit : new int[]{80, 85, 90, 95, 100}) {
            List<MeasurementRules.ChargePoint> protectedCharge = new ArrayList<>();
            for (int soc = 20; soc <= limit; soc += 2)
                protectedCharge.add(new MeasurementRules.ChargePoint(soc,
                        913000 + (soc - 20) * 45650L));
            MeasurementRules.CapacityFit protectedFit = MeasurementRules.fitCapacity(protectedCharge);
            check(protectedFit != null, "enough levels below " + limit + "% cap");
            near(4565, protectedFit.mah, "health estimate independent of " + limit + "% cap");
            check(MeasurementRules.pausedLimit(0, limit, true) == limit,
                    "auto detects stopped charging at " + limit + "%");
            check(MeasurementRules.pausedLimit(limit, limit - 1, true) == limit,
                    "manual limit accepts a one-point display tolerance at " + limit + "%");
            check(MeasurementRules.pausedLimit(limit, limit - 2, true) == -1,
                    "manual limit rejects premature stop at " + limit + "%");
            check(MeasurementRules.pausedLimit(limit, limit, false) == -1,
                    "still-charging phone cannot claim a paused limit at " + limit + "%");
        }
        check(MeasurementRules.pausedLimit(0, 73, true) == -1,
                "auto does not mistake a thermal pause for Battery Protection");
        check(MeasurementRules.pausedLimit(90, 85, true) == -1,
                "manual selection does not misclassify another limit");
        check(MeasurementRules.nextCustomStopCount(73, -1, 0) == 1, "first custom stop is only a candidate");
        check(MeasurementRules.nextCustomStopCount(74, 73, 1) == 2, "second close stop confirms custom limit");
        check(MeasurementRules.nextCustomStopCount(76, 73, 1) == 1, "unrelated stop resets learning");
        check(MeasurementRules.nextCustomStopCount(49, 73, 1) == 0, "low thermal stop is not learned");
        List<MeasurementRules.ChargePoint> longSession = new ArrayList<>();
        for (int pass = 0; pass < 200; pass++)
            for (int soc = 20; soc <= 90; soc++)
                longSession.add(new MeasurementRules.ChargePoint(soc,
                        913000 + (soc - 20) * 45650L));
        MeasurementRules.CapacityFit bounded = MeasurementRules.fitCapacity(longSession);
        near(4565, bounded.mah, "long observation history preserves capacity");
        check(bounded.pairs <= CapacityEstimator.MAX_BINS * (CapacityEstimator.MAX_BINS - 1) / 2,
                "comparison count is bounded independent of sample count");
        System.out.println("PASS: " + cases + " measurement regression cases");
    }
}
