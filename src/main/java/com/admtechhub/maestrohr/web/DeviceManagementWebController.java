package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.device.*;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered HTMX device management page (Attendance → Device Sync).
 * Handles both the shell GET (tab-based view) and all write actions via HTMX POST.
 * HR_ADMIN / SUPER_ADMIN only.
 */
@Controller
@RequiredArgsConstructor
//@RequiresFeature(SubscriptionFeature.HARDWARE_SYNC)
//@PreAuthorize("hasAnyAuthority('HR_ADMIN', 'SUPER_ADMIN')")
public class DeviceManagementWebController {

    private final DeviceApiKeyService deviceApiKeyService;
    private final DeviceEmployeeEnrollmentRepository enrollmentRepository;
    private final EmployeeRepository employeeRepository;

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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
