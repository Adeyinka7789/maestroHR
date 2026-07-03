package com.admtechhub.maestrohr.retirement;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetirementNotificationJob}'s threshold-crossing / idempotency logic.
 * Collaborators are mocked; the {@link Clock} is a real {@link Clock#fixed} instance (not
 * mocked — an unstubbed {@code @Mock Clock} NPEs inside {@code LocalDate.now(clock)}), so
 * "today" is deterministic across runs, matching the discipline used in
 * {@code AttendanceServiceTest}'s Part C fixed-clock tests.
 *
 * <p>Cross-tenant leakage itself is covered separately, end-to-end against a real database, by
 * {@link RetirementNotificationCrossTenantIsolationTest}.
 */
@ExtendWith(MockitoExtension.class)
class RetirementNotificationJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 3);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Mock private TenantRepository tenantRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private RetirementPolicyService retirementPolicyService;
    @Mock private RetirementNotificationLogRepository notificationLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    private RetirementNotificationJob job;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String HR_EMAIL = "hr@tenant.test";

    @BeforeEach
    void setUp() {
        job = new RetirementNotificationJob(
                tenantRepository, employeeRepository, retirementPolicyService,
                notificationLogRepository, userRepository, notificationService, FIXED_CLOCK);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT_ID);
        tenant.setActive(true);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        RetirementPolicy policy = RetirementPolicy.builder()
                .retirementAge(60)
                .notificationThresholdDays("180,30")
                .build();
        when(retirementPolicyService.getOrCreateDefault(TENANT_ID)).thenReturn(policy);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Only stubbed in tests that actually reach the recipient lookup (i.e. have employees to notify about). */
    private void stubHrRecipients() {
        when(userRepository.findActiveEmailsByTenantIdAndRole(TENANT_ID, UserRole.HR_ADMIN))
                .thenReturn(List.of(HR_EMAIL));
        when(userRepository.findActiveEmailsByTenantIdAndRole(TENANT_ID, UserRole.SYSTEM_ADMIN))
                .thenReturn(List.of());
    }

    private Employee employeeWithDob(LocalDate dob) {
        Employee e = Employee.builder()
                .firstName("Jane").lastName("Doe")
                .dateOfBirth(dob)
                .status(EmployeeStatus.ACTIVE)
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    @Test
    void employeeCrossesThresholdFirstTime_notifiesAndLogs() {
        stubHrRecipients();
        when(retirementPolicyService.parseThresholds("180,30")).thenReturn(List.of(180, 30));

        Employee employee = employeeWithDob(TODAY.plusDays(30).minusYears(60));
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of(employee));

        LocalDate retirementDate = TODAY.plusDays(30);
        when(retirementPolicyService.getEstimatedRetirementDate(employee)).thenReturn(Optional.of(retirementDate));
        when(notificationLogRepository.existsByEmployeeIdAndThresholdDays(employee.getId(), 30)).thenReturn(false);
        // 180-day threshold: thresholdDate = retirementDate - 180 = TODAY - 150, 150 days stale
        // > 30-day window, so shouldNotify short-circuits on the date check and never reaches
        // the exists() lookup for threshold 180 — no stub needed for it.

        job.notifyApproachingRetirements();

        verify(notificationService, times(1))
                .createInAppNotification(eq(HR_EMAIL), eq("RETIREMENT_APPROACHING"), anyString(), anyString(), anyString());
        verify(notificationLogRepository, times(1)).save(any(RetirementNotificationLog.class));
    }

    @Test
    void sameThresholdAlreadyLogged_doesNotReNotify() {
        stubHrRecipients();
        when(retirementPolicyService.parseThresholds("180,30")).thenReturn(List.of(30));

        Employee employee = employeeWithDob(TODAY.plusDays(30).minusYears(60));
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of(employee));

        LocalDate retirementDate = TODAY.plusDays(30);
        when(retirementPolicyService.getEstimatedRetirementDate(employee)).thenReturn(Optional.of(retirementDate));
        // Log row already exists from yesterday's run for this (employee, threshold) pair.
        when(notificationLogRepository.existsByEmployeeIdAndThresholdDays(employee.getId(), 30)).thenReturn(true);

        job.notifyApproachingRetirements();

        verify(notificationService, never())
                .createInAppNotification(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(notificationLogRepository, never()).save(any(RetirementNotificationLog.class));
    }

    @Test
    void employeeWithNullDateOfBirth_skippedWithoutError() {
        when(retirementPolicyService.parseThresholds("180,30")).thenReturn(List.of(180, 30));

        Employee employee = employeeWithDob(null);
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of(employee));

        assertDoesNotThrow(() -> job.notifyApproachingRetirements());

        verify(retirementPolicyService, never()).getEstimatedRetirementDate(any());
        verify(notificationService, never())
                .createInAppNotification(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(notificationLogRepository, never()).save(any(RetirementNotificationLog.class));
    }

    @Test
    void multipleThresholds_onlyTheCrossedOneFires() {
        stubHrRecipients();
        when(retirementPolicyService.parseThresholds("180,30")).thenReturn(List.of(180, 30));

        Employee employee = employeeWithDob(TODAY.plusDays(150).minusYears(60));
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of(employee));

        // Retirement date 150 days out: the 30-day threshold (100+ days from now) hasn't been
        // reached yet; the 180-day threshold date is TODAY - 30, right at the edge of the
        // stale window, so exactly that one should fire.
        LocalDate retirementDate = TODAY.plusDays(150);
        when(retirementPolicyService.getEstimatedRetirementDate(employee)).thenReturn(Optional.of(retirementDate));
        when(notificationLogRepository.existsByEmployeeIdAndThresholdDays(employee.getId(), 180)).thenReturn(false);

        job.notifyApproachingRetirements();

        verify(notificationService, times(1))
                .createInAppNotification(eq(HR_EMAIL), eq("RETIREMENT_APPROACHING"), anyString(), anyString(), anyString());
        verify(notificationLogRepository, times(1))
                .save(argThatThresholdIs(180));
        verify(notificationLogRepository, never()).existsByEmployeeIdAndThresholdDays(employee.getId(), 30);
    }

    private static RetirementNotificationLog argThatThresholdIs(int thresholdDays) {
        return org.mockito.ArgumentMatchers.argThat(log -> log != null && thresholdDays == log.getThresholdDays());
    }
}
