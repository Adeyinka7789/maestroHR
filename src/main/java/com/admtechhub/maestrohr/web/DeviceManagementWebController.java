package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.device.*;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered HTMX device management page (Attendance → Device Sync).
 * Handles both the shell GET (tab-based view) and all write actions via HTMX POST.
 * Restricted to SYSTEM_ADMIN (tenant owner), HR_ADMIN, and SUPER_ADMIN — same gate as
 * {@link AttendancePolicyController} / {@link LoanPolicyController} / {@link ShiftController}.
 *
 * The gate used to be {@code @PreAuthorize("hasAnyAuthority('HR_ADMIN', 'SUPER_ADMIN')")},
 * commented out. That expression was also broken independently of being disabled:
 * {@code hasAnyAuthority} does a raw string match against the granted authority, but every
 * authority in this app carries a {@code ROLE_} prefix (see {@code JwtAuthFilter}), so it would
 * have matched no one even if re-enabled. Replaced with a manual check (see
 * {@link #checkAccess()}) so a denial is a plain {@link AccessDeniedException} caught locally by
 * {@link #handleAccessDenied} and rendered as an in-place fragment (HTTP 200) — a raw 403 here
 * would trip layout.js's {@code htmx:responseError} handler, which clears localStorage and
 * bounces the user to login.
 */
@Controller
@RequiredArgsConstructor
public class DeviceManagementWebController {

    private final DeviceApiKeyService deviceApiKeyService;
    private final DeviceEmployeeEnrollmentRepository enrollmentRepository;
    private final EmployeeRepository employeeRepository;

    /**
     * Runs before every handler in this controller (a plain Spring MVC per-request hook, not
     * Spring Security AOP) — checked unconditionally, including the cold shell GET and every
     * write, so a caller can't bypass it by omitting the {@code HX-Request} header on a direct
     * POST. Using {@code @ModelAttribute} instead of a class-level {@code @PreAuthorize} avoids
     * that annotation also covering {@link #handleAccessDenied} below (a class-level security
     * annotation applies to every public method, including the exception handler, which would
     * re-deny the handler itself and produce the exact raw-403 this fix exists to avoid).
     */
    @ModelAttribute
    public void checkAccess() {
        if (!hasAnyRole("ROLE_SYSTEM_ADMIN", "ROLE_HR_ADMIN", "ROLE_SUPER_ADMIN")) {
            throw new AccessDeniedException("You don't have access to this page.");
        }
    }

    // ── Page shell ────────────────────────────────────────────────────────────

    @GetMapping("/htmx/attendance/devices")
    public String devicesPage(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(defaultValue = "devices") String tab,
            Model model) {

        if (htmx == null) return "forward:/layout.html";

        populateModel(model, tab, null, null, null);
        return "attendance-devices :: content";
    }

    // ── Device CRUD ───────────────────────────────────────────────────────────

    @PostMapping("/htmx/attendance/devices")
    public String createDevice(
            @RequestParam String deviceName,
            @RequestParam(required = false) String deviceIdentifier,
            @RequestParam(required = false) String location,
            Model model) {

        CreateDeviceApiKeyRequest req = new CreateDeviceApiKeyRequest();
        req.setDeviceName(deviceName);
        req.setDeviceIdentifier(deviceIdentifier);
        req.setLocation(location);

        DeviceKeyCreatedDTO created = deviceApiKeyService.createKey(req);
        populateModel(model, "devices", null,
                "Device created. Copy the key below — it will never be shown again.", created);
        return "attendance-devices :: content";
    }

    @PostMapping("/htmx/attendance/devices/{keyId}/revoke")
    public String revokeDevice(@PathVariable UUID keyId, Model model) {
        deviceApiKeyService.revokeKey(keyId);
        populateModel(model, "devices", null, "Device key revoked. The device will no longer be able to push events.", null);
        return "attendance-devices :: content";
    }

    @PostMapping("/htmx/attendance/devices/{keyId}/delete")
    public String deleteDevice(@PathVariable UUID keyId, Model model) {
        deviceApiKeyService.deleteKey(keyId);
        populateModel(model, "devices", null, "Device deleted. All enrollments for this device were also removed.", null);
        return "attendance-devices :: content";
    }

    // ── Enrollment management ─────────────────────────────────────────────────

    @PostMapping("/htmx/attendance/devices/enrollments")
    public String createEnrollment(
            @RequestParam UUID deviceApiKeyId,
            @RequestParam String deviceEmployeeId,
            @RequestParam UUID employeeId,
            Model model) {

        CreateEnrollmentRequest req = new CreateEnrollmentRequest();
        req.setDeviceApiKeyId(deviceApiKeyId);
        req.setDeviceEmployeeId(deviceEmployeeId);
        req.setEmployeeId(employeeId);

        deviceApiKeyService.createEnrollment(req);
        populateModel(model, "devices", null, "Employee enrolled on device successfully.", null);
        return "attendance-devices :: content";
    }

    @PostMapping("/htmx/attendance/devices/enrollments/{enrollmentId}/delete")
    public String deleteEnrollment(@PathVariable UUID enrollmentId, Model model) {
        deviceApiKeyService.deleteEnrollment(enrollmentId);
        populateModel(model, "devices", null, "Enrollment removed.", null);
        return "attendance-devices :: content";
    }

    // ── Sync errors ───────────────────────────────────────────────────────────

    @PostMapping("/htmx/attendance/devices/sync-errors/{errorId}/resolve")
    public String resolveError(@PathVariable UUID errorId, Model model) {
        deviceApiKeyService.resolveError(errorId);
        populateModel(model, "errors", null, "Error marked as resolved.", null);
        return "attendance-devices :: content";
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public String handleError(RuntimeException ex, Model model) {
        populateModel(model, "devices", ex.getMessage(), null, null);
        return "attendance-devices :: content";
    }

    /**
     * Renders access-denial as a data-free fragment (HTTP 200). Deliberately does NOT call
     * {@link #populateModel} like {@link #handleError} does — that would query and render the
     * device/employee data this gate exists to keep away from unauthorized roles.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True when the authenticated caller holds any of the given ROLE_* authorities. */
    private boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            for (String role : roles) {
                if (role.equals(granted.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void populateModel(Model model, String tab, String error, String success,
                               DeviceKeyCreatedDTO newKey) {
        model.addAttribute("tab", tab);
        model.addAttribute("devices", deviceApiKeyService.listKeys());
        model.addAttribute("unresolvedErrorCount", deviceApiKeyService.countUnresolvedErrors());
        model.addAttribute("syncErrors", deviceApiKeyService.listUnresolvedErrors(50));
        model.addAttribute("employees", employeeRepository.findByStatus(EmployeeStatus.ACTIVE));
        model.addAttribute("enrollmentsByDevice", buildEnrollmentMap());

        //MUST have these three lines:
        model.addAttribute("formError", error);
        model.addAttribute("success", success);
        model.addAttribute("newKey", newKey);
    }

    private java.util.Map<UUID, List<DeviceEnrollmentDTO>> buildEnrollmentMap() {
        java.util.Map<UUID, List<DeviceEnrollmentDTO>> map = new java.util.LinkedHashMap<>();
        for (DeviceApiKeyDTO device : deviceApiKeyService.listKeys()) {
            map.put(device.getId(), deviceApiKeyService.listEnrollments(device.getId()));
        }
        return map;
    }
}
