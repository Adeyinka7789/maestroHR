package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.employee.event.EmployeeCreatedEvent;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.paystack.BankCodeResolver;
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

import java.util.UUID;

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
    private final BankCodeResolver bankCodeResolver;

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
        verifyBankAccountAndCreateRecipient(employee);

        // Step 2: Send welcome notification
        sendWelcomeNotification(employee, event);
    }

    /**
     * Re-attempts Paystack verification for an employee who has no recipient code yet — e.g.
     * after HR fills in bank details that were left blank at intake (bulk import / recruitment
     * conversion both allow that). Safe to call repeatedly: no-ops if bank details are still
     * blank, and skips entirely once a recipient code is already on file.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryBankVerification(UUID employeeId) {
        Employee employee = employeeRepository.findById(employeeId).orElse(null);
        if (employee == null) {
            log.warn("Retry aborted: employee {} not found.", employeeId);
            return;
        }
        if (employee.getPaystackRecipientCode() != null && !employee.getPaystackRecipientCode().isBlank()) {
            log.info("Employee {} already has a Paystack recipient code; skipping retry.",
                    employee.getEmployeeNumber());
            return;
        }
        verifyBankAccountAndCreateRecipient(employee);
    }

    private void verifyBankAccountAndCreateRecipient(Employee employee) {
        if (isBlank(employee.getBankName()) || isBlank(employee.getBankAccountNumber())) {
            log.info("Skipping Paystack verification for employee {}: bank details not yet provided",
                    employee.getEmployeeNumber());
            return;
        }

        try {
            log.info("Verifying bank account for employee {}", employee.getEmployeeNumber());

            String bankCode = bankCodeResolver.resolveBankCode(employee.getBankName());
            var accountData = paystackClient.resolveAccount(employee.getBankAccountNumber(), bankCode);

            if (!accountData.getAccountName().equalsIgnoreCase(employee.getBankAccountName())) {
                log.warn("Account name mismatch for employee {}: Expected '{}', Got '{}'",
                        employee.getEmployeeNumber(), employee.getBankAccountName(), accountData.getAccountName());
            }

            String recipientCode = paystackClient.createTransferRecipient(
                    employee.getBankAccountName(),
                    employee.getBankAccountNumber(),
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
