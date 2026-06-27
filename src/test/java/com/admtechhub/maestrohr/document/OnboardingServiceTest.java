package com.admtechhub.maestrohr.document;

import com.admtechhub.maestrohr.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the onboarding seeding logic — the default checklist and its idempotency guard.
 * Repository access is mocked; no Spring context / DB.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock private OnboardingTaskRepository onboardingTaskRepository;
    @InjectMocks private OnboardingService onboardingService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void bindTenant() {
        TenantContext.setCurrentTenant(tenantId.toString());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void seedsTheFiveDefaultTasksInOrderWhenNoneExist() {
        when(onboardingTaskRepository.existsByEmployeeId(employeeId)).thenReturn(false);

        onboardingService.createDefaultTasksForEmployee(employeeId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OnboardingTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(onboardingTaskRepository).saveAll(captor.capture());

        List<OnboardingTask> saved = captor.getValue();
        assertEquals(OnboardingService.DEFAULT_TASKS.size(), saved.size());
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(i, saved.get(i).getTaskOrder());
            assertEquals(OnboardingService.DEFAULT_TASKS.get(i), saved.get(i).getTaskName());
            assertEquals(employeeId, saved.get(i).getEmployeeId());
            assertEquals(tenantId, saved.get(i).getTenantId());
            assertFalse(saved.get(i).isCompleted());
        }
    }

    @Test
    void isIdempotentWhenTasksAlreadyExist() {
        when(onboardingTaskRepository.existsByEmployeeId(employeeId)).thenReturn(true);

        onboardingService.createDefaultTasksForEmployee(employeeId);

        verify(onboardingTaskRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }
}
