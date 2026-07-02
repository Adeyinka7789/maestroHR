package com.admtechhub.maestrohr.employee;

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
 * Runs asynchronously so the HTTP response returns immediately.
 * Tenant context is automatically propagated by TenantContextTaskDecorator.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmployeePostCommitProcessor {

    private final EmployeeRepository employeeRepository;
    private final PaystackClient paystackClient;
    private final NotificationService notificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleEmployeePostCommitSideEffects(EmployeeCreatedEvent event) {
        log.info("Processing post-commit side effects for employee ID: {}", event.employeeId());

        Employee employee = employeeRepository.findById(event.employeeId()).orElse(null);
        if (employee == null) {
            log.error("Post-commit processing aborted: Employee {} not found.", event.employeeId());
            return;
        }

        // TenantContext is automatically propagated by TenantContextTaskDecorator
        // No manual setCurrentTenant/clear needed

        // Step 1: Verify bank account with Paystack and create transfer recipient
        verifyBankAccountAndCreateRecipient(employee, event);

        // Step 2: Send welcome notification
        sendWelcomeNotification(employee, event);
    }

    private void verifyBankAccountAndCreateRecipient(Employee employee, EmployeeCreatedEvent event) {
        try {
            log.info("Verifying bank account for employee {}", employee.getEmployeeNumber());

            String bankCode = getBankCode(event.bankName());
            var accountData = paystackClient.resolveAccount(event.bankAccountNumber(), bankCode);

            if (!accountData.getAccountName().equalsIgnoreCase(event.bankAccountName())) {
                log.warn("Account name mismatch for employee {}: Expected '{}', Got '{}'",
                        employee.getEmployeeNumber(), event.bankAccountName(), accountData.getAccountName());
            }

            String recipientCode = paystackClient.createTransferRecipient(
                    event.bankAccountName(),
                    event.bankAccountNumber(),
                    bankCode
            );

            employee.setPaystackRecipientCode(recipientCode);
            employeeRepository.save(employee);

            log.info("Successfully created Paystack recipient for employee {}: {}",
                    employee.getEmployeeNumber(), recipientCode);

        } catch (PaystackNetworkException e) {
            log.error("Paystack network error for employee {}. Payroll will handle missing codes.",
                    employee.getEmployeeNumber(), e);
        } catch (PaystackApiException e) {
            log.error("Paystack API rejection for employee {}: {}",
                    employee.getEmployeeNumber(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during Paystack verification for employee {}: {}",
                    employee.getEmployeeNumber(), e.getMessage(), e);
        }
    }

    private void sendWelcomeNotification(Employee employee, EmployeeCreatedEvent event) {
        try {
            notificationService.sendWelcomeNotification(employee, event.rawPassword());
            log.info("Welcome notification sent to employee {}", employee.getEmployeeNumber());
        } catch (Exception e) {
            log.error("Failed to send welcome notification for employee {}: {}",
                    employee.getEmployeeNumber(), e.getMessage());
        }
    }

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