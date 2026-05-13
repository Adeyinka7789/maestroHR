package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
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
            context.setVariable("employee", employee);
            context.setVariable("entry", entry);
            context.setVariable("period", period);
            context.setVariable("grossSalary", entry.getGrossSalary() / 100.0);
            context.setVariable("netSalary", entry.getNetSalary() / 100.0);
            context.setVariable("payeTax", entry.getPayeTax() / 100.0);
            context.setVariable("pensionEmployee", entry.getPensionEmployee() / 100.0);
            context.setVariable("nhfDeduction", entry.getNhfDeduction() / 100.0);

            String html = templateEngine.process("payslip", context);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(outputStream);

            return outputStream.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate payslip for employee {}: {}",
                    employee.getEmployeeNumber(), e.getMessage());
            return null;
        }
    }
}