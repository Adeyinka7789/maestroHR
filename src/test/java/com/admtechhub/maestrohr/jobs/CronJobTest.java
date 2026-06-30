package com.admtechhub.maestrohr.jobs;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.notification.EmailService;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import com.admtechhub.maestrohr.platform.JobSweepQueries.BirthdayRow;
import com.admtechhub.maestrohr.platform.JobSweepQueries.PayrollPendingRow;
import com.admtechhub.maestrohr.platform.JobSweepQueries.TrialExpiryRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CronJobTest {

    @Mock private JobSweepQueries jobSweepQueries;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private NotificationService notificationService;
    @Mock private EmployeeRepository employeeRepository;

    private TrialExpiryReminderJob trialExpiryJob;
    private BirthdayNotificationJob birthdayJob;
    private PayrollReminderJob payrollReminderJob;

    @BeforeEach
    void setUp() {
        trialExpiryJob = new TrialExpiryReminderJob(jobSweepQueries, userRepository, Optional.of(emailService));
        birthdayJob = new BirthdayNotificationJob(jobSweepQueries, userRepository, notificationService, employeeRepository);
        payrollReminderJob = new PayrollReminderJob(jobSweepQueries, userRepository, notificationService, Optional.of(emailService));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void trialExpiryJob_noExpiringTenants_doesNothing() {
        when(jobSweepQueries.findTrialingTenantsExpiringSoon()).thenReturn(List.of());

        trialExpiryJob.remindTrialExpiry();

        verify(userRepository, never()).findActiveEmailsByTenantIdAndRole(any(), any());
        verify(emailService, never()).sendEmailWithAttachment(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void trialExpiryJob_tenantExpiring7Days_sendsEmail() {
        UUID tenantId = UUID.randomUUID();
        when(jobSweepQueries.findTrialingTenantsExpiringSoon())
                .thenReturn(List.of(new TrialExpiryRow(tenantId, 7)));
        when(userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.SYSTEM_ADMIN))
                .thenReturn(List.of("admin@tenant.com"));

        trialExpiryJob.remindTrialExpiry();

        verify(emailService).sendEmailWithAttachment(
                eq("admin@tenant.com"),
                eq("Your MaestroHR trial ends in 7 days"),
                anyString(), any(), any());
    }

    @Test
    void birthdayJob_noBirthdays_doesNothing() {
        when(jobSweepQueries.findBirthdaysToday(anyInt(), anyInt())).thenReturn(List.of());

        birthdayJob.notifyBirthdays();

        verify(notificationService, never()).createInAppNotification(any(), any(), any(), any(), any());
    }

    @Test
    void birthdayJob_employeeHasBirthdayToday_notifiesHR() {
        UUID tenantId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        when(jobSweepQueries.findBirthdaysToday(anyInt(), anyInt()))
                .thenReturn(List.of(new BirthdayRow(tenantId, employeeId, "Jane", "Doe")));
        when(userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.HR_ADMIN))
                .thenReturn(List.of("hr@tenant.com"));

        birthdayJob.notifyBirthdays();

        verify(notificationService).createInAppNotification(
                "hr@tenant.com",
                "EMPLOYEE_BIRTHDAY",
                "Birthday today: Jane Doe",
                "Jane Doe's birthday is today!",
                "/employees/" + employeeId);
    }

    @Test
    void payrollReminderJob_runAlreadyExists_doesNotRemind() {
        when(jobSweepQueries.findTenantsWithPendingPayroll(anyInt(), anyInt())).thenReturn(List.of());

        payrollReminderJob.remindPayrollProcessing();

        verify(notificationService, never()).createInAppNotification(any(), any(), any(), any(), any());
    }
}
