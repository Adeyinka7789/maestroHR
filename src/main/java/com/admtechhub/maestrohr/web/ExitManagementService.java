package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.exit.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Assembles the server-rendered {@link ExitManagementView} for the HTMX exit-management page. */
@Service
@RequiredArgsConstructor
public class ExitManagementService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final NumberFormat NAIRA_FMT = NumberFormat.getCurrencyInstance(new Locale("en", "NG"));

    private final ExitService exitService;

    @Transactional(readOnly = true)
    public ExitManagementView buildList() {
        List<ExitRequestDTO> requests = exitService.getAllExitRequests();
        List<ExitManagementView.Row> rows = requests.stream().map(this::toRow).toList();
        Map<String, Object> rawStats = exitService.getStats();
        ExitManagementView.Stats stats = new ExitManagementView.Stats(
                toLong(rawStats.get("inClearance")),
                toLong(rawStats.get("clearanceComplete")),
                toLong(rawStats.get("settled")),
                toLong(rawStats.get("completed")),
                formatNaira(rawStats.get("totalPayable")));
        return new ExitManagementView(rows, stats);
    }

    private ExitManagementView.Row toRow(ExitRequestDTO r) {
        String status = r.getStatus() != null ? r.getStatus() : "IN_CLEARANCE";
        String name = r.getEmployeeName() != null ? r.getEmployeeName() : "—";
        String lastDay = r.getLastWorkingDay() != null ? r.getLastWorkingDay().format(DATE_FMT) : "—";
        return new ExitManagementView.Row(
                r.getId(),
                name,
                initials(name),
                "—",                // department not in summary DTO; shown in detail
                humanize(r.getExitType()),
                lastDay,
                status,
                humanize(status),
                statusKind(status),
                (int) Math.round(r.getClearanceProgress() * (r.getClearanceItems().size() > 0
                        ? r.getClearanceItems().size() : 1) / 100.0),
                r.getClearanceItems().size(),
                "CLEARANCE_COMPLETE".equals(status),
                "SETTLED".equals(status)
        );
    }

    private String statusKind(String status) {
        return switch (status) {
            case "COMPLETED" -> "success";
            case "CLEARANCE_COMPLETE" -> "warn";
            case "SETTLED" -> "warn";
            default -> "neutral";
        };
    }

    private String humanize(String raw) {
        if (raw == null) return "—";
        return raw.replace('_', ' ').toLowerCase(Locale.ENGLISH)
                .substring(0, 1).toUpperCase(Locale.ENGLISH)
                + raw.replace('_', ' ').toLowerCase(Locale.ENGLISH).substring(1);
    }

    private String initials(String fullName) {
        if (fullName == null || fullName.isBlank()) return "?";
        String[] parts = fullName.trim().split("\\s+");
        char a = parts[0].charAt(0);
        char b = parts.length > 1 ? parts[parts.length - 1].charAt(0) : ' ';
        return (String.valueOf(a) + b).trim().toUpperCase(Locale.ENGLISH);
    }

    private String formatNaira(Object value) {
        if (value == null) return "₦0";
        try {
            double amount = Double.parseDouble(value.toString());
            return "₦" + String.format(Locale.ENGLISH, "%,.0f", amount);
        } catch (NumberFormatException e) {
            return "₦0";
        }
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return 0L; }
    }
}
