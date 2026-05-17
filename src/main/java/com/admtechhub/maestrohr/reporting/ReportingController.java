package com.admtechhub.maestrohr.reporting;

import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportingController {

    private final ReportingService reportingService;
    private final PenComReportService penComReportService;
    private final NsitfReportService nsitfReportService;

    @GetMapping("/monthly-payroll-summary")
    public ResponseEntity<byte[]> monthlyPayrollSummary(@RequestParam Integer month,
                                                        @RequestParam Integer year,
                                                        @RequestParam(defaultValue = "PDF") ReportFormat format) {
        return download(reportingService.generateMonthlyPayrollSummary(month, year, format));
    }

    @GetMapping("/paye-schedule")
    public ResponseEntity<byte[]> payeSchedule(@RequestParam Integer month,
                                               @RequestParam Integer year,
                                               @RequestParam(defaultValue = "PDF") ReportFormat format) {
        return download(reportingService.generatePayeSchedule(month, year, format));
    }

    @GetMapping("/pension-schedule")
    public ResponseEntity<byte[]> pensionSchedule(@RequestParam Integer month,
                                                  @RequestParam Integer year,
                                                  @RequestParam(defaultValue = "PDF") ReportFormat format) {
        return download(reportingService.generatePensionSchedule(month, year, format));
    }

    @GetMapping("/nhf-schedule")
    public ResponseEntity<byte[]> nhfSchedule(@RequestParam Integer month,
                                              @RequestParam Integer year,
                                              @RequestParam(defaultValue = "PDF") ReportFormat format) {
        return download(reportingService.generateNhfSchedule(month, year, format));
    }

    @GetMapping("/employee-headcount")
    public ResponseEntity<byte[]> employeeHeadcount(@RequestParam(defaultValue = "PDF") ReportFormat format) {
        return download(reportingService.generateEmployeeHeadcountReport(format));
    }

    @GetMapping("/leave-balance")
    public ResponseEntity<byte[]> leaveBalance(@RequestParam Integer year,
                                               @RequestParam(defaultValue = "PDF") ReportFormat format) {
        return download(reportingService.generateLeaveBalanceReport(year, format));
    }

    @GetMapping("/salary-history")
    public ResponseEntity<byte[]> salaryHistory(@RequestParam UUID employeeId,
                                                @RequestParam(defaultValue = "PDF") ReportFormat format) {
        return download(reportingService.generateSalaryHistoryReport(employeeId, format));
    }

    @GetMapping("/audit-trail")
    public ResponseEntity<byte[]> auditTrail(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "PDF") ReportFormat format) {
        return download(reportingService.generateAuditTrailReport(from, to, format));
    }

    @GetMapping("/payslip")
    public ResponseEntity<?> payslip(@RequestParam UUID employeeId,
                                     @RequestParam(required = false) UUID payrollRunId) {
        try {
            ReportFile file = payrollRunId != null
                    ? reportingService.generatePayslip(employeeId, payrollRunId)
                    : reportingService.generateLatestPayslip(employeeId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(file.contentType()));
            headers.setContentDisposition(ContentDisposition.attachment().filename(file.filename()).build());
            return ResponseEntity.ok().headers(headers).body(file.content());

        } catch (IllegalArgumentException e) {
            // Catch "No payroll history found" error
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("message", "No payroll records found for this employee. Please process payroll first.");
            return ResponseEntity.status(404).body(errorResponse);

        } catch (Exception e) {
            // Catch any other errors
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("message", "Failed to generate payslip. Please try again.");
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @GetMapping("/pencom-report")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> generatePenComReport(
            @RequestParam Integer month,
            @RequestParam Integer year,
            @RequestParam(defaultValue = "PDF") ReportFormat format) {

        UUID tenantId = currentTenantId();
        String html = penComReportService.generatePenComHtml(month, year, tenantId);

        // Convert HTML to PDF using the same utility as other reports
        byte[] pdf = convertHtmlToPdf(html);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("pencom-report-" + year + "-" + String.format("%02d", month) + ".pdf")
                .build());

        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    // Add this helper method (reuse from your ReportingService or implement here)
    private byte[] convertHtmlToPdf(String html) {
        try {
            org.xhtmlrenderer.pdf.ITextRenderer renderer = new org.xhtmlrenderer.pdf.ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                renderer.createPDF(out);
                return out.toByteArray();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PDF", e);
        }
    }

    @GetMapping("/nsitf-report")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> generateNsitfReport(
            @RequestParam Integer month,
            @RequestParam Integer year,
            @RequestParam(defaultValue = "PDF") ReportFormat format) {

        UUID tenantId = currentTenantId();
        Map<String, Object> data = nsitfReportService.generateNsitfData(month, year, tenantId);

        String title = (String) data.get("title");
        String subtitle = (String) data.get("subtitle");
        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) data.get("headers");
        @SuppressWarnings("unchecked")
        List<List<String>> rows = (List<List<String>>) data.get("rows");

        byte[] pdf = reportingService.generatePdfFromData(title, subtitle, headers, rows);

        HttpHeaders headersResponse = new HttpHeaders();
        headersResponse.setContentType(MediaType.APPLICATION_PDF);
        headersResponse.setContentDisposition(ContentDisposition.attachment()
                .filename("nsitf-report-" + year + "-" + String.format("%02d", month) + ".pdf")
                .build());

        return ResponseEntity.ok().headers(headersResponse).body(pdf);
    }

    private ResponseEntity<byte[]> download(ReportFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.filename()).build());
        return ResponseEntity.ok().headers(headers).body(file.content());
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }

}
