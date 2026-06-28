package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.disbursement.SalaryPayment;
import com.admtechhub.maestrohr.disbursement.provider.CSVDisbursementProvider;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.dto.PaystackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisbursementService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final PaystackClient paystackClient;
    private final CSVDisbursementProvider csvDisbursementProvider;

    /**
     * Initiate bulk salary disbursement for an approved payroll run
     */
    @Transactional
    public PayrollRun disburseSalaries(UUID payrollRunId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        if (payrollRun.getStatus() != PayrollStatus.APPROVED) {
            throw new IllegalStateException("Payroll must be APPROVED before disbursement. Current status: " + payrollRun.getStatus());
        }

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId);

        List<PayrollEntry> pendingEntries = entries.stream()
                .filter(e -> e.getTransferStatus() == TransferStatus.PENDING)
                .toList();

        if (pendingEntries.isEmpty()) {
            throw new IllegalStateException("No pending entries found for disbursement");
        }

        List<PaystackRequest.Transfer> transfers = new ArrayList<>();

        for (PayrollEntry entry : pendingEntries) {
            String recipientCode = entry.getEmployee().getPaystackRecipientCode();
            if (recipientCode == null) {
                log.warn("Employee {} has no recipient code, skipping", entry.getEmployee().getEmployeeNumber());
                continue;
            }

            PaystackRequest.Transfer transfer = PaystackRequest.Transfer.builder()
                    .amount(entry.getNetSalary().intValue()) // Already in kobo
                    .recipient(recipientCode)
                    .reference(generateReference(entry))
                    .reason("Salary payment for " + payrollRun.getPeriod())
                    .build();

            transfers.add(transfer);
        }

        // Initiate bulk transfer
        var response = paystackClient.initiateBulkTransfer(transfers);

        // Update statuses
        payrollRun.setStatus(PayrollStatus.DISBURSING);

        // Map transfer references to entries
        if (response.getData().getTransferCodes() != null) {
            for (int i = 0; i < response.getData().getTransferCodes().size() && i < pendingEntries.size(); i++) {
                PayrollEntry entry = pendingEntries.get(i);
                entry.setTransferReference(response.getData().getTransferCodes().get(i));
                entry.setTransferStatus(TransferStatus.PENDING);
                payrollEntryRepository.save(entry);
            }
        }

        PayrollRun updated = payrollRunRepository.save(payrollRun);
        log.info("Disbursement initiated for {} entries, total amount: {}",
                transfers.size(), response.getData().getTotalAmount());

        return updated;
    }

    /**
     * Disburse via CSV export: builds the bank payment file, marks all entries PAID,
     * and sets the run COMPLETED immediately (manual bank path — no webhook confirmation).
     * Returns the raw CSV bytes for the caller to stream as a file download.
     */
    @Transactional
    public byte[] disburseSalariesCsv(UUID payrollRunId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        if (payrollRun.getStatus() != PayrollStatus.APPROVED) {
            throw new IllegalStateException(
                    "Payroll must be APPROVED before CSV disbursement. Current status: " + payrollRun.getStatus());
        }

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId);
        if (entries.isEmpty()) {
            throw new IllegalStateException("No entries found for payroll run: " + payrollRunId);
        }

        List<SalaryPayment> payments = entries.stream()
                .map(entry -> SalaryPayment.builder()
                        .employeeId(entry.getEmployee().getId().toString())
                        .employeeNumber(entry.getEmployee().getEmployeeNumber())
                        .employeeName(entry.getEmployee().getFullName())
                        .accountNumber(entry.getEmployee().getBankAccountNumber())
                        .bankCode(entry.getEmployee().getBankName())
                        .bankName(entry.getEmployee().getBankName())
                        .accountName(entry.getEmployee().getBankAccountName())
                        .amountKobo(entry.getNetSalary())
                        .reference(generateReference(entry))
                        .narration("Salary payment for " + payrollRun.getPeriod())
                        .paystackRecipientCode(entry.getEmployee().getPaystackRecipientCode())
                        .build())
                .toList();

        byte[] csvBytes = csvDisbursementProvider.generateCSVFile(payments);

        for (PayrollEntry entry : entries) {
            entry.setTransferStatus(TransferStatus.PAID);
            entry.setTransferReference(generateReference(entry));
        }
        payrollEntryRepository.saveAll(entries);

        payrollRun.setStatus(PayrollStatus.COMPLETED);
        payrollRunRepository.save(payrollRun);

        log.info("CSV disbursement complete for payroll run {}: {} entries, run COMPLETED", payrollRunId, entries.size());
        return csvBytes;
    }

    private String generateReference(PayrollEntry entry) {
        String tenantPrefix = entry.getPayrollRun().getTenant().getId().toString().substring(0, 8);
        return String.format("SAL-%s-%s-%s",
                tenantPrefix,
                entry.getPayrollRun().getPeriod(),
                entry.getEmployee().getEmployeeNumber());
    }
}