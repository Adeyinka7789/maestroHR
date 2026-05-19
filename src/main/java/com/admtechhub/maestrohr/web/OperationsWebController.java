package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.reporting.ReportFile;
import com.admtechhub.maestrohr.reporting.ReportingService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Routes for operations pages: departments, pay-grades, leave, attendance,
 * payroll, reports, subscribers, admin.
 *
 * All page data is fetched client-side via REST APIs — model attributes
 * were previously computed here then discarded by the redirect, so they
 * have been removed. The payslip download endpoint is the only one that
 * still does real server work and is unchanged.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping
@Transactional(readOnly = true)
public class OperationsWebController {

    private final ReportingService reportingService;
    private final TenantRepository tenantRepository;

    // ── Page routes ───────────────────────────────────────────────────────────

    @GetMapping("/departments")
    public String departments(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/departments.html" : "forward:/layout.html";
    }

    @GetMapping("/pay-grades")
    public String payGrades(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/pay-grades.html" : "forward:/layout.html";
    }

    @GetMapping("/leave")
    public String leave(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/leave.html" : "forward:/layout.html";
    }

    @GetMapping("/attendance")
    public String attendance(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/attendance.html" : "forward:/layout.html";
    }

    @GetMapping("/payroll")
    public String payroll(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/payroll.html" : "forward:/layout.html";
    }

    @GetMapping("/payroll/{id}")
    public String payrollDetails(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        // The id is a path variable but the static page reads it from
        // window.location.pathname client-side, so we just serve the shell or partial.
        return htmx != null ? "forward:/payroll-detail.html" : "forward:/layout.html";
    }

    @GetMapping("/reports")
    public String reports(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/reports.html" : "forward:/layout.html";
    }

    @GetMapping("/subscribers")
    public String subscribers(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        // subscribers.html is a Thymeleaf template, not a static file.
        // Keep serving it as a full Thymeleaf render — exclude from HTMX layout.
        return "redirect:/subscribers";
    }

    @GetMapping("/admin")
    public String admin(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/admin.html" : "forward:/layout.html";
    }

    // ── Payslip download (unchanged — real server work) ───────────────────────

    @GetMapping("/reports/payslip")
    public ResponseEntity<byte[]> payslipDownload(
            @RequestParam UUID employeeId,
            @RequestParam(required = false) UUID payrollRunId) {
        ReportFile file = payrollRunId != null
                ? reportingService.generatePayslip(employeeId, payrollRunId)
                : reportingService.generateLatestPayslip(employeeId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    // ── Tenant helper ─────────────────────────────────────────────────────────

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available for operations page");
        }
        return UUID.fromString(tenantId);
    }
}