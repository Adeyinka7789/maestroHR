package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.event.EmployeeCreatedEvent;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackApiException;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackNetworkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Handles side effects that must execute AFTER the employee creation transaction commits.
 *
 * - Paystack account verification and transfer recipient creation
 * - Welcome notification dispatch
 *
 * These run asynchronously so the HTTP response returns immediately.
 * Failures here do NOT roll back employee creation - they're logged for retry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmployeePostCommitProcessor {

    private final EmployeeRepository employeeRepository;
    private final PaystackClient paystackClient;
    private final NotificationService notificationService;

    /**
     * Process employee creation side effects asynchronously after DB commit.
     *
     * Uses @Async to free the request thread immediately.
     * Uses REQUIRES_NEW to run in its own transaction (isolated from the event publisher).
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleEmployeePostCommitSideEffects(EmployeeCreatedEvent event) {
        log.info("Processing post-commit side effects for employee ID: {}", event.employeeId());

        // Fetch employee in the new async thread context
        Employee employee = employeeRepository.findById(event.employeeId()).orElse(null);
        if (employee == null) {
            log.error("Post-commit processing aborted: Employee {} not found.", event.employeeId());
            return;
        }

        // Restore tenant context for downstream services that may use TenantContext
        if (employee.getTenant() != null) {
            TenantContext.setCurrentTenant(employee.getTenant().getId().toString());
        }

        // Step 1: Verify bank account with Paystack and create transfer recipient
        verifyBankAccountAndCreateRecipient(employee, event);

        // Step 2: Send welcome notification
        sendWelcomeNotification(employee, event);
    }

    /**
     * Verify bank account with Paystack and create transfer recipient.
     * Updates the employee record with the recipient code on success.
     */
    private void verifyBankAccountAndCreateRecipient(Employee employee, EmployeeCreatedEvent event) {
        try {
            log.info("Verifying bank account for employee {}", employee.getEmployeeNumber());

            String bankCode = getBankCode(event.bankName());
            var accountData = paystackClient.resolveAccount(event.bankAccountNumber(), bankCode);

            // Warn on name mismatch but don't block
            if (!accountData.getAccountName().equalsIgnoreCase(event.bankAccountName())) {
                log.warn("Account name mismatch for employee {}: Expected '{}', Got '{}'",
                        employee.getEmployeeNumber(), event.bankAccountName(), accountData.getAccountName());
            }

            // Create transfer recipient
            String recipientCode = paystackClient.createTransferRecipient(
                    event.bankAccountName(),
                    event.bankAccountNumber(),
                    bankCode
            );

            // Update employee with recipient code
            employee.setPaystackRecipientCode(recipientCode);
            employeeRepository.save(employee);

            log.info("Successfully created Paystack recipient for employee {}: {}",
                    employee.getEmployeeNumber(), recipientCode);

        } catch (PaystackNetworkException e) {
            log.error("Paystack network error for employee {}. " +
                            "Recipient code not set. Payroll will handle missing codes.",
                    employee.getEmployeeNumber(), e);

        } catch (PaystackApiException e) {
            log.error("Paystack API error for employee {}. Bank details may be invalid: {}",
                    employee.getEmployeeNumber(), e.getMessage());

        } catch (Exception e) {
            log.error("Unexpected error during Paystack verification for employee {}: {}",
                    employee.getEmployeeNumber(), e.getMessage(), e);
        }
    }

    /**
     * Send welcome notification email to the new employee.
     */
    private void sendWelcomeNotification(Employee employee, EmployeeCreatedEvent event) {
        try {
            notificationService.sendWelcomeNotification(employee, event.rawPassword());
            log.info("Welcome notification sent to employee {}", employee.getEmployeeNumber());
        } catch (Exception e) {
            log.error("Failed to send welcome notification for employee {}: {}",
                    employee.getEmployeeNumber(), e.getMessage());
            // Non-critical - employee can use password reset if email fails
        }
    }

    /**
     * Map bank name to CBN (Central Bank of Nigeria) bank code.
     */
    private String getBankCode(String bankName) {
        Map<String, String> bankCodes = Map.ofEntries(
                Map.entry("GTBank", "058"), Map.entry("GTB", "058"), Map.entry("Guaranty Trust Bank", "058"),
                Map.entry("First Bank", "011"), Map.entry("FirstBank", "011"),
                Map.entry("UBA", "033"), Map.entry("United Bank For Africa", "033"),
                Map.entry("Access Bank", "044"), Map.entry("Access", "044"),
                Map.entry("Zenith Bank", "057"), Map.entry("Zenith", "057"),
                Map.entry("Union Bank", "032"), Map.entry("Union", "032"),
                Map.entry("FCMB", "214"), Map.entry("First City Monument Bank", "214"),
                Map.entry("Stanbic IBTC", "221"), Map.entry("Stanbic", "221"),
                Map.entry("Sterling Bank", "232"), Map.entry("Sterling", "232"),
                Map.entry("Polaris Bank", "076"), Map.entry("Polaris", "076"),
                Map.entry("Ecobank", "050"), Map.entry("Eco", "050")
        );

        String code = bankCodes.get(bankName);
        if (code != null) return code;

        for (Map.Entry<String, String> entry : bankCodes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(bankName)) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException("Bank not supported: " + bankName);
    }
}