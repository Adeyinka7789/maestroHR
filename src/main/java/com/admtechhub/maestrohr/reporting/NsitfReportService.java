package com.admtechhub.maestrohr.reporting;

import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NsitfReportService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;

    private static final double NSITF_RATE = 0.01;

    @Transactional(readOnly = true)
    public Map<String, Object> generateNsitfData(Integer month, Integer year, UUID tenantId) {
        PayrollRun payrollRun = payrollRunRepository
                .findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(tenantId, month, year)
                .orElseThrow(() -> new IllegalArgumentException("No payroll run found for " + month + "/" + year));

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRun.getId());
        Tenant tenant = payrollRun.getTenant();

        List<String> headers = List.of("Employee Number", "Employee Name", "Gross Salary (₦)", "NSITF Contribution (₦)");
        List<List<String>> rows = new ArrayList<>();
        long totalGross = 0;
        long totalNsitf = 0;

        for (PayrollEntry entry : entries) {
            long gross = entry.getGrossSalary();
            long nsitf = Math.round(gross * NSITF_RATE);
            totalGross += gross;
            totalNsitf += nsitf;
            rows.add(List.of(
                    entry.getEmployee().getEmployeeNumber(),
                    entry.getEmployee().getFullName(),
                    formatCurrency(gross),
                    formatCurrency(nsitf)
            ));
        }
        // Add totals row
        rows.add(List.of("TOTAL", "", formatCurrency(totalGross), formatCurrency(totalNsitf)));

        Map<String, Object> result = new HashMap<>();
        result.put("title", "NSITF Contribution Report");
        result.put("subtitle", tenant.getCompanyName() + " – " + getMonthName(month) + " " + year + " (Rate: 1%)");
        result.put("headers", headers);
        result.put("rows", rows);
        return result;
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
}