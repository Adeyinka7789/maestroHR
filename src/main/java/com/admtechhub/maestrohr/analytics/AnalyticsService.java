package com.admtechhub.maestrohr.analytics;

import com.admtechhub.maestrohr.analytics.AnalyticsView.BurnoutRow;
import com.admtechhub.maestrohr.analytics.AnalyticsView.DeptRcolRow;
import com.admtechhub.maestrohr.analytics.AnalyticsView.SpikeRow;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.overtime.OvertimeEntry;
import com.admtechhub.maestrohr.overtime.OvertimeEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Executive analytics over existing payroll / leave / overtime data (no new tables). Three lenses:
 * <ul>
 *   <li><b>Real Cost of Labor</b> — the true employer cost of the latest finalized run
 *       (gross + employer pension + NSITF 1% + ITF 1%), per department and company-wide;</li>
 *   <li><b>Departmental payroll spikes</b> — month-over-month employer payroll cost per department
 *       (latest vs prior finalized run), flagging rises past the threshold, with overtime called out;</li>
 *   <li><b>Burnout / attrition risk</b> — rules-based (not ML): tenured active staff who have taken
 *       no approved leave in over a year, and/or logged heavy overtime recently.</li>
 * </ul>
 * NSITF/ITF rates and the thresholds are constants here (future: tenant-configurable). Health
 * insurance is excluded from RCOL because it is not tracked.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final List<PayrollStatus> FINALIZED = List.of(
            PayrollStatus.APPROVED, PayrollStatus.DISBURSING,
            PayrollStatus.DISBURSING_UNKNOWN, PayrollStatus.COMPLETED);

    private static final long NSITF_BP = 1;   // 1% employer levy on gross
    private static final long ITF_BP = 1;     // 1% employer levy on gross
    private static final double SPIKE_THRESHOLD_PCT = 10.0;
    private static final double SPIKE_SEVERE_PCT = 25.0;
    private static final int BURNOUT_NO_LEAVE_MONTHS = 12;
    private static final BigDecimal BURNOUT_OVERTIME_HOURS = new BigDecimal("24");
    private static final int OVERTIME_LOOKBACK_MONTHS = 3;
    private static final int TREND_MONTHS = 6;
    // Sparkline geometry (SVG user units); the template scales it to the card width.
    private static final double SVG_W = 320, SVG_H = 70, SVG_PAD_X = 8, SVG_PAD_TOP = 10, SVG_PAD_BOTTOM = 10;

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final OvertimeEntryRepository overtimeEntryRepository;

    @Transactional(readOnly = true)
    public AnalyticsView build() {
        List<PayrollRun> runs = payrollRunRepository.findByStatusInOrderByPeriodDesc(FINALIZED);
        if (runs.isEmpty()) {
            return empty();
        }

        PayrollRun latest = runs.get(0);
        List<PayrollEntry> latestEntries = payrollEntryRepository.findByPayrollRunIdWithEntities(latest.getId());

        // ── Real Cost of Labor ──
        long totalGross = 0, totalEmpPension = 0, totalNsitf = 0, totalItf = 0, totalRcol = 0;
        Map<String, long[]> deptRcolAgg = new LinkedHashMap<>(); // dept -> [rcol, headcount]
        Map<UUID, String> empDept = new java.util.HashMap<>();

        for (PayrollEntry e : latestEntries) {
            long gross = n(e.getGrossSalary());
            long empPension = n(e.getPensionEmployer());
            long nsitf = gross * NSITF_BP / 100;
            long itf = gross * ITF_BP / 100;
            long rcol = gross + empPension + nsitf + itf;

            totalGross += gross;
            totalEmpPension += empPension;
            totalNsitf += nsitf;
            totalItf += itf;
            totalRcol += rcol;

            String dept = departmentName(e.getEmployee());
            long[] agg = deptRcolAgg.computeIfAbsent(dept, k -> new long[2]);
            agg[0] += rcol;
            agg[1] += 1;
            if (e.getEmployee() != null) {
                empDept.put(e.getEmployee().getId(), dept);
            }
        }

        int headcount = latestEntries.size();
        long avgRcol = headcount > 0 ? totalRcol / headcount : 0;
        final long totalRcolFinal = totalRcol;
        List<DeptRcolRow> deptRcol = deptRcolAgg.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .map(en -> new DeptRcolRow(en.getKey(), (int) en.getValue()[1],
                        formatNaira(en.getValue()[0]), percentOf(en.getValue()[0], totalRcolFinal)))
                .toList();

        String latestLabel = periodLabel(latest);

        // ── RCOL trend sparkline (last few finalized runs, oldest → newest) ──
        int trendCount = Math.min(TREND_MONTHS, runs.size());
        List<Long> trendValues = new ArrayList<>(trendCount);
        List<String> trendLabels = new ArrayList<>(trendCount);
        for (int i = trendCount - 1; i >= 0; i--) {   // runs is newest-first; walk back for chronological order
            PayrollRun r = runs.get(i);
            long rcol = (i == 0) ? totalRcol
                    : rcolForEntries(payrollEntryRepository.findByPayrollRunIdWithEntities(r.getId()));
            trendValues.add(rcol);
            trendLabels.add(shortPeriodLabel(r));
        }
        Trend trend = buildTrend(trendValues, trendLabels);

        // ── Departmental payroll spikes ──
        boolean hasComparison = runs.size() >= 2;
        String priorLabel = "";
        List<SpikeRow> spikes = List.of();
        if (hasComparison) {
            PayrollRun prior = runs.get(1);
            priorLabel = periodLabel(prior);
            Map<String, Long> curCost = deptCost(latestEntries);
            Map<String, Long> priCost = deptCost(payrollEntryRepository.findByPayrollRunIdWithEntities(prior.getId()));
            Map<String, Long> deptOvertime = overtimeByDept(latest.getPayrollYear(), latest.getPayrollMonth(), empDept);
            spikes = buildSpikes(curCost, priCost, deptOvertime);
        }

        // ── Burnout / attrition risk ──
        List<BurnoutRow> burnout = buildBurnout();

        return new AnalyticsView(true, latestLabel,
                formatNaira(totalRcol), formatNaira(totalGross), formatNaira(totalEmpPension),
                formatNaira(totalNsitf), formatNaira(totalItf), headcount, formatNaira(avgRcol), deptRcol,
                trend.hasTrend(), trend.points(), trend.linePoints(), trend.areaPoints(),
                hasComparison, priorLabel, spikes,
                burnout.size(), burnout);
    }

    // ── RCOL export (raw kobo breakdown for CSV/Excel) ──────────────────────────

    /** Per-department RCOL breakdown (raw kobo) for the latest finalized run, plus a totals row. */
    @Transactional(readOnly = true)
    public RcolReport buildRcolReport() {
        List<PayrollRun> runs = payrollRunRepository.findByStatusInOrderByPeriodDesc(FINALIZED);
        if (runs.isEmpty()) {
            return new RcolReport(false, "", List.of(), null);
        }
        PayrollRun latest = runs.get(0);
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunIdWithEntities(latest.getId());

        // dept -> [headcount, gross, employerPension, nsitf, itf, rcol]
        Map<String, long[]> agg = new LinkedHashMap<>();
        long tHead = 0, tGross = 0, tPension = 0, tNsitf = 0, tItf = 0, tRcol = 0;
        for (PayrollEntry e : entries) {
            long gross = n(e.getGrossSalary());
            long pension = n(e.getPensionEmployer());
            long nsitf = gross * NSITF_BP / 100;
            long itf = gross * ITF_BP / 100;
            long rcol = gross + pension + nsitf + itf;
            long[] a = agg.computeIfAbsent(departmentName(e.getEmployee()), k -> new long[6]);
            a[0]++;
            a[1] += gross;
            a[2] += pension;
            a[3] += nsitf;
            a[4] += itf;
            a[5] += rcol;
            tHead++;
            tGross += gross;
            tPension += pension;
            tNsitf += nsitf;
            tItf += itf;
            tRcol += rcol;
        }

        List<RcolReport.Row> rows = agg.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[5], x.getValue()[5]))
                .map(en -> {
                    long[] a = en.getValue();
                    return new RcolReport.Row(en.getKey(), (int) a[0], a[1], a[2], a[3], a[4], a[5]);
                })
                .toList();
        RcolReport.Row totals = new RcolReport.Row("TOTAL", (int) tHead, tGross, tPension, tNsitf, tItf, tRcol);
        return new RcolReport(true, periodLabel(latest), rows, totals);
    }

    // ── RCOL trend geometry ─────────────────────────────────────────────────────

    private record Trend(boolean hasTrend, List<AnalyticsView.TrendPoint> points,
                         String linePoints, String areaPoints) {}

    /** Total employer cost (RCOL) for a run's entries: gross + employer pension + NSITF + ITF. */
    private long rcolForEntries(List<PayrollEntry> entries) {
        long total = 0;
        for (PayrollEntry e : entries) {
            long gross = n(e.getGrossSalary());
            total += gross + n(e.getPensionEmployer()) + gross * NSITF_BP / 100 + gross * ITF_BP / 100;
        }
        return total;
    }

    /** Turn RCOL values into an SVG polyline + baseline-closed area + labelled points. */
    private Trend buildTrend(List<Long> values, List<String> labels) {
        int n = values.size();
        if (n < 2) {
            return new Trend(false, List.of(), "", "");
        }
        long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
        double plotW = SVG_W - 2 * SVG_PAD_X;
        double plotH = SVG_H - SVG_PAD_TOP - SVG_PAD_BOTTOM;
        double baseline = SVG_H - SVG_PAD_BOTTOM;

        List<AnalyticsView.TrendPoint> points = new ArrayList<>(n);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double cx = SVG_PAD_X + i * plotW / (n - 1);
            double frac = (max == min) ? 0.5 : (double) (values.get(i) - min) / (max - min);
            double cy = baseline - frac * plotH;
            if (i > 0) {
                line.append(' ');
            }
            line.append(coord(cx)).append(',').append(coord(cy));
            points.add(new AnalyticsView.TrendPoint(labels.get(i), formatNaira(values.get(i)),
                    Double.parseDouble(coord(cx)), Double.parseDouble(coord(cy))));
        }
        String linePoints = line.toString();
        String areaPoints = coord(SVG_PAD_X) + "," + coord(baseline) + " " + linePoints + " "
                + coord(SVG_PAD_X + plotW) + "," + coord(baseline);
        return new Trend(true, points, linePoints, areaPoints);
    }

    /** SVG coordinate with a dot decimal separator (never a locale comma, which would break paths). */
    private String coord(double v) {
        return String.format(Locale.ENGLISH, "%.1f", v);
    }

    /** Short month label for the sparkline axis, e.g. "Jul '26". */
    private String shortPeriodLabel(PayrollRun run) {
        return Month.of(run.getPayrollMonth()).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                + " '" + String.format("%02d", run.getPayrollYear() % 100);
    }

    // ── spikes ──────────────────────────────────────────────────────────────────

    /** Employer payroll cost (gross + employer pension) per department for a run's entries. */
    private Map<String, Long> deptCost(List<PayrollEntry> entries) {
        Map<String, Long> byDept = new LinkedHashMap<>();
        for (PayrollEntry e : entries) {
            byDept.merge(departmentName(e.getEmployee()), n(e.getGrossSalary()) + n(e.getPensionEmployer()), Long::sum);
        }
        return byDept;
    }

    /** Approved overtime amount (kobo) per department for a period. */
    private Map<String, Long> overtimeByDept(int year, int month, Map<UUID, String> empDept) {
        Map<String, Long> byDept = new LinkedHashMap<>();
        for (OvertimeEntry o : overtimeEntryRepository.findByPeriodYearAndPeriodMonthOrderByAmountKoboDesc(year, month)) {
            if (o.getStatus() != com.admtechhub.maestrohr.overtime.OvertimeStatus.APPROVED) {
                continue;
            }
            byDept.merge(empDept.getOrDefault(o.getEmployeeId(), "Unassigned"), o.getAmountKobo(), Long::sum);
        }
        return byDept;
    }

    private List<SpikeRow> buildSpikes(Map<String, Long> cur, Map<String, Long> pri, Map<String, Long> deptOvertime) {
        TreeSet<String> depts = new TreeSet<>();
        depts.addAll(cur.keySet());
        depts.addAll(pri.keySet());

        List<SpikeRow> rows = new ArrayList<>();
        for (String dept : depts) {
            long c = cur.getOrDefault(dept, 0L);
            long p = pri.getOrDefault(dept, 0L);
            if (c == 0 && p == 0) {
                continue;
            }
            String pct;
            String kind;
            boolean flagged;
            double pctVal;
            if (p == 0) {
                pct = "new";
                kind = "warn";
                flagged = true;
                pctVal = 100.0;
            } else {
                pctVal = (c - p) * 100.0 / p;
                pct = (pctVal >= 0 ? "+" : "−") + Math.round(Math.abs(pctVal)) + "%";
                flagged = pctVal >= SPIKE_THRESHOLD_PCT;
                kind = pctVal >= SPIKE_SEVERE_PCT ? "error" : (flagged ? "warn" : "neutral");
            }
            long ot = deptOvertime.getOrDefault(dept, 0L);
            String note = flagged && ot > 0 ? "incl. " + formatNaira(ot) + " overtime" : "";
            rows.add(new SpikeRow(dept, formatNaira(c), formatNaira(p), pct, kind, flagged, note));
        }
        // Flagged first, then biggest movers.
        rows.sort(Comparator.comparing(SpikeRow::flagged).reversed()
                .thenComparing(r -> r.department()));
        return rows;
    }

    // ── burnout ─────────────────────────────────────────────────────────────────

    private List<BurnoutRow> buildBurnout() {
        LocalDate today = LocalDate.now();

        Map<UUID, LocalDate> lastLeave = new java.util.HashMap<>();
        for (Object[] row : leaveRequestRepository.findLastApprovedLeaveEndDateByEmployee()) {
            if (row[0] != null && row[1] != null) {
                lastLeave.put((UUID) row[0], (LocalDate) row[1]);
            }
        }

        int cutoffKey = (today.getYear() * 12 + today.getMonthValue()) - (OVERTIME_LOOKBACK_MONTHS - 1);
        Map<UUID, BigDecimal> otHours = new java.util.HashMap<>();
        for (OvertimeEntry o : overtimeEntryRepository.findApprovedSincePeriodKey(cutoffKey)) {
            otHours.merge(o.getEmployeeId(),
                    o.getWeekdayOtHours().add(o.getWeekendOtHours()), BigDecimal::add);
        }

        List<BurnoutRow> rows = new ArrayList<>();
        for (Employee emp : employeeRepository.findByStatus(EmployeeStatus.ACTIVE)) {
            List<String> reasons = new ArrayList<>();
            boolean severe = false;

            // Only flag "no leave" for tenured staff (employed > the window), so new hires aren't flagged.
            boolean tenured = emp.getEmploymentStartDate() != null
                    && emp.getEmploymentStartDate().isBefore(today.minusMonths(BURNOUT_NO_LEAVE_MONTHS));
            if (tenured) {
                LocalDate last = lastLeave.get(emp.getId());
                if (last == null) {
                    reasons.add("No approved leave on record");
                } else if (last.isBefore(today.minusMonths(BURNOUT_NO_LEAVE_MONTHS))) {
                    long months = ChronoUnit.MONTHS.between(last, today);
                    reasons.add("No approved leave in " + months + " months");
                }
            }

            BigDecimal ot = otHours.getOrDefault(emp.getId(), BigDecimal.ZERO);
            if (ot.compareTo(BURNOUT_OVERTIME_HOURS) > 0) {
                reasons.add(ot.stripTrailingZeros().toPlainString() + "h overtime (last "
                        + OVERTIME_LOOKBACK_MONTHS + " months)");
            }

            if (!reasons.isEmpty()) {
                severe = reasons.size() > 1;
                rows.add(new BurnoutRow(emp.getId(), emp.getFullName(),
                        emp.getDepartment() != null ? emp.getDepartment().getName() : "Unassigned",
                        String.join("; ", reasons), severe ? "error" : "warn"));
            }
        }
        rows.sort(Comparator.comparing(BurnoutRow::severityKind).thenComparing(BurnoutRow::name));
        return rows;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private AnalyticsView empty() {
        return new AnalyticsView(false, "", "₦0", "₦0", "₦0", "₦0", "₦0",
                0, "₦0", List.of(),
                false, List.of(), "", "",
                false, "", List.of(), 0, List.of());
    }

    private String departmentName(Employee e) {
        return e != null && e.getDepartment() != null ? e.getDepartment().getName() : "Unassigned";
    }

    private long n(Long v) {
        return v != null ? v : 0L;
    }

    private String percentOf(long part, long total) {
        if (total <= 0) {
            return "0%";
        }
        return Math.round(part * 100.0 / total) + "%";
    }

    private String periodLabel(PayrollRun run) {
        return Month.of(run.getPayrollMonth()).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + run.getPayrollYear();
    }

    private String formatNaira(long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", kobo / 100);
    }
}
