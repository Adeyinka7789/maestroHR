package com.admtechhub.maestrohr.reporting;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PenComReportService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;

    @Transactional(readOnly = true)
    public String generatePenComHtml(Integer month, Integer year, UUID tenantId) {
        PayrollRun payrollRun = payrollRunRepository
                .findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(tenantId, month, year)
                .orElseThrow(() -> new IllegalArgumentException("No payroll run found for " + month + "/" + year));

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRun.getId());
        Tenant tenant = payrollRun.getTenant();

        return buildHtml(entries, tenant, month, year);
    }

    private String buildHtml(List<PayrollEntry> entries, Tenant tenant, Integer month, Integer year) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'/><title>PenCom Contribution Report</title>");
        html.append("<style>");
        html.append("body { font-family: Arial, sans-serif; margin: 20px; }");
        html.append(".header { text-align: center; margin-bottom: 30px; }");
        html.append(".company-name { font-size: 24px; font-weight: bold; color: #1e3a8a; }");
        html.append(".report-title { font-size: 18px; margin-top: 5px; color: #374151; }");
        html.append(".period { font-size: 14px; color: #6b7280; margin-top: 5px; }");
        html.append("table { width: 100%; border-collapse: collapse; margin-top: 20px; }");
        html.append("th, td { border: 1px solid #d1d5db; padding: 10px; text-align: left; }");
        html.append("th { background: #f3f4f6; font-weight: bold; }");
        html.append("tr:nth-child(even) { background: #f9fafb; }");
        html.append(".footer { margin-top: 30px; text-align: center; font-size: 12px; color: #9ca3af; }");
        html.append("</style></head><body>");

        html.append("<div class='header'>");
        html.append("<div class='company-name'>").append(escapeHtml(tenant.getCompanyName())).append("</div>");
        html.append("<div class='report-title'>PenCom RSA Contribution Report</div>");
        html.append("<div class='period'>").append(getMonthName(month)).append(" ").append(year).append("</div>");
        html.append("</div>");

        html.append("<table>");
        html.append("<thead><tr>");
        html.append("<th>RSA PIN</th><th>Employee Number</th><th>Employee Name</th>");
        html.append("<th>Employee Contribution (₦)</th><th>Employer Contribution (₦)</th><th>Total Contribution (₦)</th>");
        html.append("</tr></thead><tbody>");

        long totalEmployee = 0, totalEmployer = 0, totalGrand = 0;
        for (PayrollEntry entry : entries) {
            if (entry.getPensionEmployee() == 0 && entry.getPensionEmployer() == 0) continue;
            Employee emp = entry.getEmployee();
            long empContrib = entry.getPensionEmployee();
            long erContrib = entry.getPensionEmployer();
            long total = empContrib + erContrib;
            totalEmployee += empContrib;
            totalEmployer += erContrib;
            totalGrand += total;

            html.append("<tr>");
            html.append("<td>").append(escapeHtml(getRsaPin(emp))).append("</td>");
            html.append("<td>").append(escapeHtml(emp.getEmployeeNumber())).append("</td>");
            html.append("<td>").append(escapeHtml(emp.getFullName())).append("</td>");
            html.append("<td class='amount'>").append(formatCurrency(empContrib)).append("</td>");
            html.append("<td class='amount'>").append(formatCurrency(erContrib)).append("</td>");
            html.append("<td class='amount'>").append(formatCurrency(total)).append("</td>");
            html.append("</tr>");
        }

        html.append("</tbody>");
        html.append("<tfoot><tr style='background:#f3f4f6; font-weight:bold;'>");
        html.append("<td colspan='3'>Totals</td>");
        html.append("<td class='amount'>").append(formatCurrency(totalEmployee)).append("</td>");
        html.append("<td class='amount'>").append(formatCurrency(totalEmployer)).append("</td>");
        html.append("<td class='amount'>").append(formatCurrency(totalGrand)).append("</td>");
        html.append("</tr></tfoot>");
        html.append("</table>");

        html.append("<div class='footer'>");
        html.append("<p>This document is computer-generated and does not require a signature.</p>");
        html.append("<p>Generated on: ").append(java.time.LocalDate.now()).append("</p>");
        html.append("</div>");
        html.append("</body></html>");

        return html.toString();
    }

    private String getRsaPin(Employee employee) {
        String empNum = employee.getEmployeeNumber();
        if (empNum != null && empNum.length() > 4) {
            return "PEN-" + empNum.substring(empNum.length() - 6);
        }
        return "PEN-" + employee.getId().toString().substring(0, 8);
    }

    private String getMonthName(int month) {
        String[] months = {"January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"};
        return months[month - 1];
    }

    private String formatCurrency(Long amountInKobo) {
        if (amountInKobo == null) return "0.00";
        return String.format("%,.2f", amountInKobo / 100.0);
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}