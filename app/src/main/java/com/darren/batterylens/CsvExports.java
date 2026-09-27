package com.darren.batterylens;

import java.util.List;

/** Portable, locale-independent CSV output. Missing readings remain empty cells. */
final class CsvExports {
    private CsvExports() { }

    static String trends(List<TrendPoint> points) {
        StringBuilder csv = new StringBuilder("time_utc_ms,elapsed_ms,boot,level_percent,current_ma,temperature_c,plugged,interactive,hotspot_marked\n");
        for (TrendPoint p : points) csv.append(p.time).append(',').append(p.elapsed).append(',').append(p.boot).append(',')
                .append(MeasurementRules.validSoc(p.soc) ? p.soc : "").append(',')
                .append(Double.isFinite(p.currentMa) ? p.currentMa : "").append(',')
                .append(Double.isFinite(p.temperatureC) ? p.temperatureC : "").append(',').append(p.plugged).append(',')
                .append(p.screenKnown ? p.interactive : "").append(',').append(p.hotspotMarked).append('\n');
        return csv.toString();
    }

    static String sessions(List<CalibrationStore.Session> sessions) {
        StringBuilder csv = new StringBuilder("start_time_utc_ms,end_time_utc_ms,start_percent,end_percent,capacity_mah,eligible,observations,min_temp_c,max_temp_c,source,reason,endpoint_capacity_mah,fit_spread,fit_pairs,load_reversals,paused_ms,charge_duration_ms,plug_type,hotspot_marked,start_voltage_mv,end_voltage_mv\n");
        for (CalibrationStore.Session s : sessions) {
            csv.append(s.startTime).append(',').append(s.endTime).append(',').append(s.startSoc).append(',').append(s.endSoc).append(',')
                    .append(Double.isFinite(s.fccMah) ? s.fccMah : "").append(',').append(s.used).append(',').append(s.samples).append(',')
                    .append(Double.isFinite(s.minTemp) ? s.minTemp : "").append(',').append(Double.isFinite(s.maxTemp) ? s.maxTemp : "").append(',')
                    .append(cell(s.source)).append(',').append(cell(s.reason)).append(',')
                    .append(Double.isFinite(s.endpointFccMah) ? s.endpointFccMah : "").append(',')
                    .append(Double.isFinite(s.fitSpread) ? s.fitSpread : "").append(',')
                    .append(s.fitPairs).append(',').append(s.reversals).append(',').append(s.pausedMs).append(',')
                    .append(s.durationMs()).append(',').append(s.plugType).append(',').append(s.hotspotMarked).append(',')
                    .append(s.startVoltageMv > 0 ? s.startVoltageMv : "").append(',')
                    .append(s.endVoltageMv > 0 ? s.endVoltageMv : "").append('\n');
        }
        return csv.toString();
    }

    private static String cell(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }
}
