package com.admtechhub.maestrohr.analytics;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the Executive / CEO analytics dashboard
 * ({@code /htmx/analytics}), rendered as {@code analytics :: content}. Three lenses over existing
 * payroll / leave / overtime data — Real Cost of Labor, departmental month-over-month payroll
 * spikes, and rules-based burnout/attrition risk. Money is pre-formatted as naira.
 */
public record AnalyticsView(
        boolean hasData,               // false when there is no finalized payroll run yet
        String latestPeriodLabel,

        // ── Real Cost of Labor (latest finalized run) ──
        String totalRcolFormatted,
        String totalGrossFormatted,
        String totalEmployerPensionFormatted,
        String totalNsitfFormatted,
        String totalItfFormatted,
        int headcount,
        String avgRcolFormatted,
        List<DeptRcolRow> deptRcol,

        // Real Cost of Labor trend over the last few finalized runs (oldest → newest). The SVG
        // geometry is precomputed here so the template just drops it into a sparkline.
        boolean hasTrend,
        List<TrendPoint> rcolTrend,
        String rcolTrendLinePoints,   // SVG polyline "x,y x,y …"
        String rcolTrendAreaPoints,   // SVG polygon (line closed to the baseline)

        // ── Departmental payroll spikes (latest vs prior finalized run) ──
        boolean hasComparison,
        String priorPeriodLabel,
        List<SpikeRow> spikes,

        // ── Burnout & retention risk ──
        int burnoutCount,
        List<BurnoutRow> burnout
) {

    public record DeptRcolRow(String department, int headcount, String rcolFormatted, String sharePercent) {}

    /** One month on the RCOL trend sparkline: label + value + its plotted SVG coordinates. */
    public record TrendPoint(String periodLabel, String rcolFormatted, double cx, double cy) {}

    public record SpikeRow(
            String department,
            String currentFormatted,
            String priorFormatted,
            String changePercent,   // e.g. "+18%" / "−4%" / "new"
            String changeKind,      // error (big rise) / warn (rise) / neutral
            boolean flagged,
            String note) {}         // e.g. "incl. ₦450,000 overtime", or ""

    public record BurnoutRow(
            UUID employeeId,
            String name,
            String department,
            String reasons,         // "No approved leave in 14 months; 52.0h overtime (last 3 months)"
            String severityKind) {} // error / warn

    public boolean hasDeptRcol() {
        return deptRcol != null && !deptRcol.isEmpty();
    }

    public boolean hasSpikes() {
        return spikes != null && !spikes.isEmpty();
    }

    public boolean hasBurnout() {
        return burnout != null && !burnout.isEmpty();
    }
}
