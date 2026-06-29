package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.kafka.NotificationEvent;
import com.admtechhub.maestrohr.kafka.NotificationProducer;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.tenant.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock PayslipGenerator        payslipGenerator;
    @Mock TermiiClient            termiiClient;
    @Mock InAppNotificationRepository inAppNotificationRepository;
    @Mock NotificationProducer    notificationProducer;
    @Mock EmailService            emailService;

    @Mock Employee    employee;
    @Mock PayrollEntry entry;
    @Mock PayrollRun  payrollRun;
    @Mock Tenant      tenant;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                payslipGenerator, termiiClient, inAppNotificationRepository,
                Optional.of(emailService), notificationProducer);

        when(employee.getId()).thenReturn(UUID.randomUUID());
        when(employee.getEmail()).thenReturn("alice@example.com");
        when(employee.getFirstName()).thenReturn("Alice");
        when(employee.getLastName()).thenReturn("Johnson");
        when(employee.getFullName()).thenReturn("Alice Johnson");
        when(employee.getPhone()).thenReturn("08012345678");
        when(employee.getTenant()).thenReturn(tenant);
        when(tenant.getCompanyName()).thenReturn("Acme Corp");

        when(entry.getEmployee()).thenReturn(employee);
        when(entry.getPayrollRun()).thenReturn(payrollRun);
        when(entry.getNetSalary()).thenReturn(500_000L);
        when(payrollRun.getId()).thenReturn(UUID.randomUUID());
        when(payrollRun.getTenant()).thenReturn(tenant);
        when(payrollRun.getPayrollMonth()).thenReturn(6);
        when(payrollRun.getPayrollYear()).thenReturn(2025);

        when(payslipGenerator.generatePayslip(any(), any(), anyString()))
                .thenReturn(new byte[]{1, 2, 3});
    }

    @AfterEach
    void tearDown() {
        com.admtechhub.maestrohr.auth.TenantContext.clear();
    }

    @Test
    void sendPayslipNotification_kafkaAvailable_publishesToKafka() {
        when(notificationProducer.publish(any())).thenReturn(true);

        notificationService.sendPayslipNotification(entry, employee, "2025-06");

        verify(notificationProducer, atLeastOnce()).publish(any(NotificationEvent.class));
        verify(emailService, never()).sendTemplatedEmailWithAttachment(
                anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void sendPayslipNotification_kafkaUnavailable_fallsBackToEmail() {
        when(notificationProducer.publish(any())).thenReturn(false);

        notificationService.sendPayslipNotification(entry, employee, "2025-06");

        verify(emailService).sendTemplatedEmailWithAttachment(
                anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void sendWelcomeNotification_sendsEmailAndInAppNotification() {
        when(notificationProducer.publish(any())).thenReturn(true);

        notificationService.sendWelcomeNotification(employee, "TempPass123!");

        verify(notificationProducer, atLeastOnce()).publish(any(NotificationEvent.class));
        verify(inAppNotificationRepository).save(any());
    }

    @Test
    void sendLeaveApprovedEmail_kafkaAvailable_publishesToKafka() {
        when(notificationProducer.publish(any())).thenReturn(true);

        notificationService.sendLeaveApprovedEmail(
                employee, "Annual Leave", "2025-06-01", "2025-06-05", 5);

        verify(notificationProducer).publish(any(NotificationEvent.class));
        verify(emailService, never()).sendTemplatedEmail(anyString(), anyString(), anyString(), any());
    }

    @Test
    void sendLeaveRejectedEmail_sendsViaKafkaOrFallback() {
        when(notificationProducer.publish(any())).thenReturn(false);

        notificationService.sendLeaveRejectedEmail(employee, "Annual Leave", "Insufficient balance");

        verify(emailService).sendTemplatedEmail(anyString(), anyString(), anyString(), any());
    }
}
