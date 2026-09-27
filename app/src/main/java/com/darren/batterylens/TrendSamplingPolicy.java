package com.darren.batterylens;

/** Pure decision: only record a five-minute checkpoint or a context transition. */
final class TrendSamplingPolicy {
    static final long SAMPLE_MS = 5 * 60_000L;

    private TrendSamplingPolicy() { }

    static boolean shouldRecord(TrendPoint previous, TrendPoint next) {
        if (previous == null) return true;
        return previous.boot != next.boot || next.elapsed < previous.elapsed
                || next.elapsed - previous.elapsed >= SAMPLE_MS
                || previous.plugged != next.plugged
                || !previous.screenKnown || previous.screenKnown != next.screenKnown
                || previous.interactive != next.interactive
                || previous.hotspotMarked != next.hotspotMarked;
    }
}
