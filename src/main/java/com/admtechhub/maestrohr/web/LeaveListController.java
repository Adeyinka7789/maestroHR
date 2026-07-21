package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.leave.LeaveService;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.subscription.FeatureNotAvailableException;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Leave list route (Option 3 — server-rendered fragment), mirroring the
 * departments / pay-grades pages. The HTMX request renders templates/leave.html
 * with the approval-queue / history table already in the markup (no /api/leave
 * double-fetch, no loading flash); a non-HTMX visit returns the static layout shell
 * and layout.js re-requests this route with the HX-Request header.
 *
 * This is the HR-admin / manager-facing view. A status filter (All / Pending /
 * Approved / Rejected) plus a free-text search are driven by
 * {@code /htmx/leave/table}, which swaps the chip strip + table fragment.
 *
 * SCOPE (Step C): read-only list + status filter + search (Step A), per-row approve/reject
 * writes (Step B), and the "Apply for Leave" form (Step C — POST /htmx/leave/apply, with an
 * IDOR guard locking a self-service EMPLOYEE to their own record). static/leave.html remains
 * on disk as the legacy fallback until this fragment is browser-verified.
 *
 * NOTE: named {@code LeaveListController} (not {@code LeaveController}) on purpose —
 * the REST API controller {@link com.admtechhub.maestrohr.leave.LeaveController}
 * already owns the default {@code leaveController} bean name; a second bean with that
 * name would fail context startup.
 */
@Controller
@RequiredArgsConstructor
public class LeaveListController {

    private final LeaveListService leaveListService;
    private final LeaveService leaveService;
    private final UserRepository userRepository;
    private final EmployeeService employeeService;
    private final FeatureAccessService featureAccessService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/leave")
    public String leave(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        // Gate the read the same way the REST LeaveController and the write actions here are gated:
        // if LEAVE_MANAGEMENT is off for this tenant (kill switch, per-tenant override, or plan), the
        // page must not render. Placed after the cold-load guard so a direct visit still gets the
        // shell; handleActionFailure turns the resulting FeatureAccessException into an in-place banner.
        featureAccessService.require(SubscriptionFeature.LEAVE_MANAGEMENT);

        model.addAttribute("view", leaveListService.buildContent(q, parseStatus(status)));
        return "leave :: content";
    }

    /** Chip strip + table only — the swap target for search and the status chips. */
    @GetMapping("/htmx/leave/table")
    public String table(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        featureAccessService.require(SubscriptionFeature.LEAVE_MANAGEMENT);
        model.addAttribute("view", leaveListService.buildList(q, parseStatus(status)));
        return "leave :: table";
    }

    /**
     * Approve a pending request, then re-render the chip strip + table so the row
     * leaves the Pending view and the chip counts refresh. The active filter/search
     * ride along via hx-include so the post-approve view matches what the user was on.
     * A status != PENDING request raises {@link IllegalStateException}, caught below
     * (the double-click "already processed" race) and rendered as an in-place banner.
     */
    @PostMapping("/htmx/leave/{id}/approve")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER')")
    @RequiresFeature(SubscriptionFeature.LEAVE_MANAGEMENT)
    public String approve(
            @PathVariable UUID id,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        leaveService.approveLeaveRequest(id, currentUserId(), null);
        model.addAttribute("view", leaveListService.buildList(q, parseStatus(status)));
        return "leave :: table";
    }

    /** Reject a pending request with a reason (from the inline textarea), then re-render. */
    @PostMapping("/htmx/leave/{id}/reject")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER')")
    @RequiresFeature(SubscriptionFeature.LEAVE_MANAGEMENT)
    public String reject(
            @PathVariable UUID id,
            @RequestParam("reason") String reason,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        leaveService.rejectLeaveRequest(id, reason);
        model.addAttribute("view", leaveListService.buildList(q, parseStatus(status)));
        return "leave :: table";
    }

    /**
     * Apply for Leave (Step C). Submits a new PENDING request for {@code employeeId} via
     * {@link LeaveService#submitLeaveRequest} (cover-officer omitted for now), then re-renders
     * the whole {@code content} fragment so the new row is visible and the chip counts refresh.
     *
     * IDOR guard: a self-service EMPLOYEE may only apply for their own record. If the caller
     * holds the EMPLOYEE role, {@code employeeId} must equal their own session employee id —
     * otherwise an {@link AccessDeniedException} (403) is raised before any write, mirroring the
     * payslip / self-attendance "resolve from the session, never trust the param" rule. HR/admin
     * roles may apply on behalf of anyone in the tenant.
     *
     * Submission validation failures (insufficient balance, overlapping leave, unknown
     * employee/type, reversed/blank dates) are caught here and rendered as an in-place
     * {@code formError} banner on the rebuilt content — they target {@code #page-content}, so
     * (unlike approve/reject) they must NOT fall through to {@link #handleActionFailure}, which
     * returns the {@code table} fragment.
     */
    @PostMapping("/htmx/leave/apply")
    @RequiresFeature(SubscriptionFeature.LEAVE_MANAGEMENT)
    public String apply(
            @RequestParam("employeeId") UUID employeeId,
            @RequestParam("leaveTypeId") UUID leaveTypeId,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        if (isEmployeeRole() && !employeeId.equals(currentEmployeeId())) {
            throw new AccessDeniedException("You can only apply for your own leave");
        }

        try {
            LocalDate start = parseRequiredDate(startDate, "Start date");
            LocalDate end = parseRequiredDate(endDate, "End date");
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("End date cannot be before the start date.");
            }
            leaveService.submitLeaveRequest(employeeId, leaveTypeId, start, end, reason, null);
            model.addAttribute("success", "Leave request submitted.");
        } catch (IllegalStateException | IllegalArgumentException ex) {
            model.addAttribute("formError", ex.getMessage());
        }

        model.addAttribute("view", leaveListService.buildContent(q, parseStatus(status)));
        return "leave :: content";
    }

    /**
     * Renders write-action failures as an in-place HTML fragment (HTTP 200, so HTMX still
     * performs the swap — it skips swaps on 4xx/5xx by default) instead of letting them reach
     * the JSON {@code GlobalExceptionHandler}. Two shapes:
     *
     * <ul>
     *   <li>{@link FeatureNotAvailableException} (the HTTP 402 raised when the tenant's plan
     *       lacks LEAVE_MANAGEMENT — note LEAVE_MANAGEMENT is a PROFESSIONAL+ feature): rebuilds
     *       the whole {@code content} fragment with a {@code formError} banner
     *       ("Upgrade your plan to access LEAVE_MANAGEMENT"). This matches the Apply form's
     *       {@code #page-content} target; without it the JSON 402 blanks the page since HTMX
     *       won't swap a 402.</li>
     *   <li>{@link IllegalStateException} / {@link IllegalArgumentException} (the approve/reject
     *       guards — most often the double-click "not pending" race): rebuilds the {@code table}
     *       fragment with an {@code error} banner, matching those buttons' {@code #leave-table}
     *       target.</li>
     * </ul>
     *
     * The active filter/search are recovered from the request params that hx-include sent with
     * the failed POST.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class,
            FeatureAccessException.class})
    public String handleActionFailure(RuntimeException ex, HttpServletRequest request, Model model) {
        String q = request.getParameter("q");
        String status = request.getParameter("status");

        if (ex instanceof FeatureAccessException) {
            model.addAttribute("view", leaveListService.buildContent(q, parseStatus(status)));
            model.addAttribute("formError", ex.getMessage());
            return "leave :: content";
        }

        model.addAttribute("view", leaveListService.buildList(q, parseStatus(status)));
        model.addAttribute("error", ex.getMessage());
        return "leave :: table";
    }

    /** Resolve the authenticated user's id to record as the approver. */
    private UUID currentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email))
                .getId();
    }

    /** True when the authenticated user holds the EMPLOYEE role (the self-service surface). */
    private boolean isEmployeeRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EMPLOYEE".equals(a.getAuthority()));
    }

    /**
     * The authenticated user's own employee id, or null for admin/owner accounts with no
     * Employee record. Used only by the EMPLOYEE-role IDOR guard; a null here means the
     * {@code !employeeId.equals(null)} check denies the apply (an employee with no profile
     * has nothing to apply against).
     */
    private UUID currentEmployeeId() {
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            return employeeService.findByEmail(email).getId();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Parse a required ISO date from the form, surfacing a friendly message on blank/garbage input. */
    private LocalDate parseRequiredDate(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(label + " is not a valid date.");
        }
    }

    /**
     * Maps the status query param to a {@link LeaveStatus} filter:
     *   - absent (null)  → default to PENDING (the approval queue is the landing view);
     *   - blank ("")     → null = the "All" chip;
     *   - valid name     → that status;
     *   - unknown value  → null = All (show everything rather than silently hide data).
     */
    private LeaveStatus parseStatus(String value) {
        if (value == null) {
            return LeaveStatus.PENDING;
        }
        if (value.isBlank()) {
            return null;
        }
        try {
            return LeaveStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
