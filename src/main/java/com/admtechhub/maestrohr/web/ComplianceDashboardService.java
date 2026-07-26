package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.document.DocumentService;
import com.admtechhub.maestrohr.document.EmployeeDocumentSummary;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles the {@link ComplianceDashboardView} for the Compliance &amp; Expiry page. All reads
 * are tenant-scoped (RLS + the entity {@code @SQLRestriction}) — this is the interactive HR view,
 * distinct from the scheduler's privileged cross-tenant sweeps in {@code platform.*Queries}
 * (which drive the daily alert jobs and see every tenant).
 *
 * <p>Two horizons: probation reviews due within 30 days (or already overdue), and documents
 * expiring within 90 days (or already expired). The document section only populates when the
 * tenant's plan/flag includes {@code DOCUMENT_VAULT}; otherwise it renders a locked notice.
 */
@Service
@RequiredArgsConstructor
public class ComplianceDashboardService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    private static final int PROBATION_HORIZON_DAYS = 30;
    private static final int DOCUMENT_HORIZON_DAYS = 90;

    private final EmployeeRepository employeeRepository;
    private final DocumentService documentService;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public ComplianceDashboardView build() {
        LocalDate today = LocalDate.now();

        // ── Probation reviews due ────────────────────────────────────────────────
        List<Employee> due = employeeRepository.findProbationDueThrough(
                today.plusDays(PROBATION_HORIZON_DAYS), EmployeeStatus.TERMINATED);

        List<ComplianceDashboardView.ProbationRow> probationRows = new ArrayList<>(due.size());
        int overdue = 0, soon = 0, later = 0;
        for (Employee e : due) {
            long days = ChronoUnit.DAYS.between(today, e.getProbationEndDate());
            String label;
            String kind;
            if (days < 0) {
                label = "Overdue by " + (-days) + "d";
                kind = "error";
                overdue++;
            } else if (days <= 7) {
                label = days == 0 ? "Due today" : "Due in " + days + "d";
                kind = "warn";
                soon++;
            } else {
                label = "Due in " + days + "d";
                kind = "neutral";
                later++;
            }
            probationRows.add(new ComplianceDashboardView.ProbationRow(
                    e.getId(), e.getFullName(), orDash(e.getJobTitle()),
                    e.getProbationEndDate().format(DATE_FORMAT), days, label, kind));
        }

        // ── Document & contract expiries (gated on DOCUMENT_VAULT) ────────────────
        boolean documentsAvailable = featureAccessService.isAvailable(SubscriptionFeature.DOCUMENT_VAULT);
        List<ComplianceDashboardView.DocumentRow> documentRows = new ArrayList<>();
        int expired = 0, exp30 = 0, exp90 = 0;

        if (documentsAvailable) {
            List<EmployeeDocumentSummary> docs =
                    documentService.getExpiringThrough(today.plusDays(DOCUMENT_HORIZON_DAYS));
            Map<UUID, String> names = resolveEmployeeNames(docs);
            for (EmployeeDocumentSummary d : docs) {
                long days = ChronoUnit.DAYS.between(today, d.getExpiryDate());
                String label;
                String kind;
                if (days < 0) {
                    label = "Expired " + (-days) + "d ago";
                    kind = "error";
                    expired++;
                } else if (days <= 30) {
                    label = days == 0 ? "Expires today" : "Expires in " + days + "d";
                    kind = "warn";
                    exp30++;
                } else {
                    label = "Expires in " + days + "d";
                    kind = "neutral";
                    exp90++;
                }
                documentRows.add(new ComplianceDashboardView.DocumentRow(
                        d.getEmployeeId(),
                        names.getOrDefault(d.getEmployeeId(), "Unknown employee"),
                        humanize(d.getDocumentType().name()),
                        d.getFileName(),
                        d.getExpiryDate().format(DATE_FORMAT),
                        days, label, kind));
            }
        }

        return new ComplianceDashboardView(
                overdue, soon, later, probationRows,
                documentsAvailable, expired, exp30, exp90, documentRows);
    }

    /** Map each document's owning employee id to a display name (tenant-scoped lookup). */
    private Map<UUID, String> resolveEmployeeNames(List<EmployeeDocumentSummary> docs) {
        Set<UUID> ids = docs.stream()
                .map(EmployeeDocumentSummary::getEmployeeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return employeeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Employee::getId, Employee::getFullName, (a, b) -> a));
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** Turn "WORK_PERMIT" into "Work Permit". */
    private String humanize(String raw) {
        String spaced = raw.replace('_', ' ').trim().toLowerCase(Locale.ENGLISH);
        StringBuilder sb = new StringBuilder(spaced.length());
        boolean cap = true;
        for (char c : spaced.toCharArray()) {
            if (Character.isWhitespace(c)) {
                cap = true;
                sb.append(c);
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
