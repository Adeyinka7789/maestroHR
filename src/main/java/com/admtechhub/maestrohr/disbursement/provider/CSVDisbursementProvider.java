package com.admtechhub.maestrohr.disbursement.provider;

import com.admtechhub.maestrohr.disbursement.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CSVDisbursementProvider implements DisbursementProvider {

    @Override
    public DisbursementResult initiateBulkTransfer(List<SalaryPayment> payments) {
        log.info("Generating CSV file for {} payments", payments.size());

        String csvContent = generateCSV(payments);

        return DisbursementResult.builder()
                .success(true)
                .batchReference(generateBatchReference())
                .message("CSV file generated successfully. Please download and upload to your bank.")
                .build();
    }

    public byte[] generateCSVFile(List<SalaryPayment> payments) {
        String csv = generateCSV(payments);
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private String generateCSV(List<SalaryPayment> payments) {
        StringBuilder sb = new StringBuilder();

        // Header row
        sb.append("Employee Number,Employee Name,Account Number,Bank Code,Amount (NGN),Reference,Narration\n");

        // Data rows
        for (SalaryPayment payment : payments) {
            sb.append(String.format("%s,%s,%s,%s,%.2f,%s,%s\n",
                    escapeCSV(payment.getEmployeeNumber()),
                    escapeCSV(payment.getEmployeeName()),
                    payment.getAccountNumber(),
                    payment.getBankCode(),
                    payment.getAmountKobo() / 100.0,
                    payment.getReference(),
                    escapeCSV(payment.getNarration())
            ));
        }

        return sb.toString();
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String generateBatchReference() {
        return "CSV_" + System.currentTimeMillis();
    }

    @Override
    public DisbursementStatus checkStatus(String reference) {
        return DisbursementStatus.SUCCESS;
    }

    @Override
    public AccountVerificationResult verifyAccount(String accountNumber, String bankCode) {
        // CSV provider cannot verify accounts via API
        return AccountVerificationResult.builder()
                .verified(true)
                .accountNumber(accountNumber)
                .message("Manual verification required")
                .build();
    }

    @Override
    public String getProviderName() {
        return "CSV";
    }

    @Override
    public boolean isConfigured(UUID tenantId) {
        return true; // CSV always available
    }
}