package com.admtechhub.maestrohr.disbursement;

import com.admtechhub.maestrohr.config.PaystackConfig;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.DisbursementPageView;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.RunOption;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.StatusCount;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.ValidationResult;
import com.admtechhub.maestrohr.payroll.DisbursementService;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import com.admtechhub.maestrohr.payroll.TransferStatus;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Direct Bank Payouts page (server-rendered HTMX fragment). Dual-control disbursement: a run is
 * created/approved by HR in Payroll; here a FINANCE_OFFICER / SUPER_ADMIN validates every account
 * against Paystack name-enquiry and then, once the run is APPROVED and structurally clean, triggers
 * the bulk transfer through the existing {@link DisbursementService}. A prominent LIVE-mode banner
 * warns when real money will move. Gated on {@link SubscriptionFeature#BASIC_PAYROLL}.
 */
@Controller
@RequiredArgsConstructor
public class PayoutController {

    private static final List<PayrollStatus> DISBURSABLE = List.of(
            PayrollStatus.APPROVED, PayrollStatus.DISBURSING, PayrollStatus.DISBURSING_UNKNOWN);

    // Dual control: plain HR_ADMIN (who approves the run) cannot also move the money here.
    private static final String[] PAYOUT_ROLES = {
            "ROLE_FINANCE_OFFICER", "ROLE_SUPER_ADMIN", "ROLE_SYSTEM_ADMIN"
    };

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final DisbursementService disbursementService;
    private final PayoutValidationService payoutValidationService;
    private final PaystackConfig paystackConfig;
    private final FeatureAccessService featureAccessService;
    private final com.admtechhub.maestrohr.audit.AuditTrailService auditTrailService;

    @GetMapping("/htmx/disbursement")
    public String disbursement(@RequestHeader(value = "HX-Request", required = false) String htmx,
                               @RequestParam(value = "runId", required = false) UUID runId,
                               Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        gate();
        model.addAttribute("view", buildView(runId, null));
        return "disbursement :: content";
    }

    /** Pre-run NUBAN validation — hits Paystack name-enquiry per employee, then re-renders. */
    @PostMapping("/htmx/disbursement/{runId}/validate")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    @RequiresFeature(SubscriptionFeature.BASIC_PAYROLL)
    public String validate(@PathVariable UUID runId, Model model) {
        ValidationResult result = payoutValidationService.validateRun(runId);
        model.addAttribute("success", "Validated " + result.rows().size() + " account(s): "
                + result.okCount() + " OK, " + result.warnCount() + " to review, " + result.errorCount() + " blocking.");
        model.addAttribute("view", buildView(runId, result));
        return "disbursement :: content";
    }

    /**
     * 1-click bulk disbursement via Paystack. Dual-control (FINANCE/SUPER/SYSTEM admin), run must be
     * APPROVED, and no structural blockers (missing bank details) — checked server-side so the guard
     * never depends on the UI. Delegates the money movement to the existing, transaction-safe
     * {@link DisbursementService#disburseSalaries}.
     */
    @PostMapping("/htmx/disbursement/{runId}/disburse")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    @RequiresFeature(SubscriptionFeature.BASIC_PAYROLL)
    public String disburse(@PathVariable UUID runId, Model model) {
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found."));
        if (run.getStatus() != PayrollStatus.APPROVED) {
            throw new IllegalStateException("Only an APPROVED run can be disbursed. Current status: " + run.getStatus());
        }
        int blockers = payoutValidationService.structuralBlockerCount(runId);
        if (blockers > 0) {
            throw new IllegalStateException(blockers + " employee(s) have missing bank details. "
                    + "Fix those before disbursing.");
        }

        UUID tenantId = currentTenantId();
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(runId, tenantId);
        long amount = entries.stream().mapToLong(e -> e.getNetSalary() != null ? e.getNetSalary() : 0L).sum();

        disbursementService.disburseSalaries(runId);

        // Audit the money movement (who initiated, how much, how many, which Paystack mode) —
        // dual-control accountability for a live payout. Best-effort; never blocks the payout.
        auditTrailService.record(tenantId, currentUserEmail(), "DISBURSEMENT_INITIATED",
                "PAYROLL_RUN", runId.toString(), "/htmx/disbursement/" + runId + "/disburse", "POST",
                null, 200, String.format("Bulk disbursement initiated: %s to %d employee(s), Paystack mode %s",
                        formatNaira(amount), entries.size(), paystackConfig.getMode()));

        model.addAttribute("success", "Bulk transfer initiated. Individual statuses update as Paystack confirms each transfer.");
        model.addAttribute("view", buildView(runId, null));
        return "disbursement :: content";
    }

    // ── failure rendering ──────────────────────────────────────────────────────────

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleFailure(RuntimeException ex,
                                @RequestParam(value = "runId", required = false) UUID runId,
                                Model model) {
        model.addAttribute("error", ex.getMessage());
        model.addAttribute("view", buildView(runId, null));
        return "disbursement :: content";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Direct Bank Payouts");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }

    // ── assembly ─────────────────────────────────────────────────────────────────

    private DisbursementPageView buildView(UUID runId, ValidationResult validation) {
        List<PayrollRun> runs = payrollRunRepository.findByStatusInOrderByPeriodDesc(DISBURSABLE);
        List<RunOption> runOptions = runs.stream()
                .map(r -> new RunOption(r.getId(), periodLabel(r), humanize(r.getStatus().name())))
                .toList();

        UUID selected = runId != null ? runId : (runs.isEmpty() ? null : runs.get(0).getId());
        String periodLabel = "";
        String status = "";
        boolean approved = false;
        int count = 0;
        long amount = 0;
        List<StatusCount> statusCounts = List.of();

        if (selected != null) {
            PayrollRun run = payrollRunRepository.findById(selected).orElse(null);
            if (run != null) {
                periodLabel = periodLabel(run);
                status = humanize(run.getStatus().name());
                approved = run.getStatus() == PayrollStatus.APPROVED;
                List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(selected, currentTenantId());
                count = entries.size();
                amount = entries.stream().mapToLong(e -> e.getNetSalary() != null ? e.getNetSalary() : 0L).sum();
                statusCounts = statusCounts(entries);
            }
        }

        return new DisbursementPageView(paystackConfig.getMode(), paystackConfig.isLiveMode(),
                runOptions, selected, periodLabel, status, approved, count, formatNaira(amount),
                statusCounts, validation);
    }

    private List<StatusCount> statusCounts(List<PayrollEntry> entries) {
        java.util.Map<TransferStatus, Integer> counts = new java.util.EnumMap<>(TransferStatus.class);
        for (PayrollEntry e : entries) {
            counts.merge(e.getTransferStatus(), 1, Integer::sum);
        }
        List<StatusCount> out = new java.util.ArrayList<>();
        for (TransferStatus s : TransferStatus.values()) {
            int c = counts.getOrDefault(s, 0);
            if (c > 0) {
                out.add(new StatusCount(humanize(s.name()), c, statusKind(s)));
            }
        }
        return out;
    }

    private String statusKind(TransferStatus s) {
        return switch (s) {
            case PAID -> "success";
            case FAILED, REVERSED -> "error";
            case DISBURSING -> "warn";
            default -> "neutral"; // PENDING
        };
    }

    private void gate() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = false;
        if (auth != null) {
            for (GrantedAuthority granted : auth.getAuthorities()) {
                for (String role : PAYOUT_ROLES) {
                    if (role.equals(granted.getAuthority())) {
                        allowed = true;
                    }
                }
            }
        }
        if (!allowed) {
            throw new AccessDeniedException("Bulk disbursement is restricted to Finance / admin roles.");
        }
        featureAccessService.require(SubscriptionFeature.BASIC_PAYROLL);
    }

    private String periodLabel(PayrollRun run) {
        return Month.of(run.getPayrollMonth()).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + run.getPayrollYear();
    }

    private String humanize(String raw) {
        String lower = raw.replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String formatNaira(long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", kobo / 100);
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
