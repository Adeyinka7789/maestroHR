package com.admtechhub.maestrohr.analytics;

import java.util.List;

/**
 * Raw (kobo) Real-Cost-of-Labor breakdown for the latest finalized run, per department plus a
 * totals row — the data behind the CSV / Excel exports. Kept separate from {@link AnalyticsView}
 * (which pre-formats everything for display); exports need the numbers.
 */
public record RcolReport(boolean hasData, String periodLabel, List<Row> rows, Row totals) {

    /** One department's cost breakdown; {@code department} is "TOTAL" on the totals row. */
    public record Row(String department, int headcount, long grossKobo, long employerPensionKobo,
                      long nsitfKobo, long itfKobo, long rcolKobo) {}
}
