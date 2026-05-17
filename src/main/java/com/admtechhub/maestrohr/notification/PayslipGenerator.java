package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayslipGenerator {

    private final TemplateEngine templateEngine;

    public byte[] generatePayslip(PayrollEntry entry, Employee employee, String period) {
        try {
            Context context = new Context();

            // Employee details
            context.setVariable("employeeFullName", employee.getFullName());
            context.setVariable("employeeNumber", employee.getEmployeeNumber());
            context.setVariable("jobTitle", employee.getJobTitle());
            context.setVariable("bankName", employee.getBankName() != null ? employee.getBankName() : "-");
            context.setVariable("accountNumber", employee.getBankAccountNumber() != null ? employee.getBankAccountNumber() : "-");
            context.setVariable("period", period);

            // Department
            if (employee.getDepartment() != null) {
                context.setVariable("department", employee.getDepartment().getName());
            } else {
                context.setVariable("department", "-");
            }

            // Payroll Entry values (convert from kobo to Naira)
            long basicSalary = entry.getBasicSalary() != null ? entry.getBasicSalary() : 0L;
            long housingAllowance = entry.getHousingAllowance() != null ? entry.getHousingAllowance() : 0L;
            long transportAllowance = entry.getTransportAllowance() != null ? entry.getTransportAllowance() : 0L;
            long otherAllowances = entry.getOtherAllowances() != null ? entry.getOtherAllowances() : 0L;
            long grossEarnings = entry.getGrossSalary() != null ? entry.getGrossSalary() : 0L;
            long payeTax = entry.getPayeTax() != null ? entry.getPayeTax() : 0L;
            long pensionEmployee = entry.getPensionEmployee() != null ? entry.getPensionEmployee() : 0L;
            long nhfDeduction = entry.getNhfDeduction() != null ? entry.getNhfDeduction() : 0L;
            long netPay = entry.getNetSalary() != null ? entry.getNetSalary() : 0L;

            context.setVariable("basicSalary", basicSalary / 100.0);
            context.setVariable("housingAllowance", housingAllowance / 100.0);
            context.setVariable("transportAllowance", transportAllowance / 100.0);
            context.setVariable("otherAllowances", otherAllowances / 100.0);
            context.setVariable("grossEarnings", grossEarnings / 100.0);
            context.setVariable("payeTax", payeTax / 100.0);
            context.setVariable("pensionEmployee", pensionEmployee / 100.0);
            context.setVariable("nhfDeduction", nhfDeduction / 100.0);

            long totalDeductions = payeTax + pensionEmployee + nhfDeduction;
            context.setVariable("totalDeductions", totalDeductions / 100.0);
            context.setVariable("netPay", netPay / 100.0);

            String html = templateEngine.process("payslip", context);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(outputStream);

            return outputStream.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate payslip for employee {}: {}",
                    employee.getEmployeeNumber(), e.getMessage(), e);
            return null;
        }
    }
}