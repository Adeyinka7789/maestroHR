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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock private OnboardingTaskRepository onboardingTaskRepository;
    @InjectMocks private OnboardingService onboardingService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID tenantId   = UUID.randomUUID();

    @BeforeEach
    void bindTenant() { TenantContext.setCurrentTenant(tenantId.toString()); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ── 1 ────────────────────────────────────────────────────────────────────

    @Test
    void createDefaultTasks_createsExactly5Tasks() {
        when(onboardingTaskRepository.existsByEmployeeId(employeeId)).thenReturn(false);

        onboardingService.createDefaultTasksForEmployee(employeeId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OnboardingTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(onboardingTaskRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(OnboardingService.DEFAULT_TASKS.size());
    }

    // ── 2 ────────────────────────────────────────────────────────────────────

    @Test
    void createDefaultTasks_idempotent_doesNotDuplicate() {
        when(onboardingTaskRepository.existsByEmployeeId(employeeId)).thenReturn(true);

        onboardingService.createDefaultTasksForEmployee(employeeId);

        verify(onboardingTaskRepository, never()).saveAll(anyList());
    }

    // ── 3 ────────────────────────────────────────────────────────────────────

    @Test
    void completeTask_setsCompletedAt() {
        UUID taskId = UUID.randomUUID();
        OnboardingTask task = OnboardingTask.builder()
                .employeeId(employeeId).tenantId(tenantId)
                .taskName("Upload Documents").taskOrder(0).completed(false).build();
        when(onboardingTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        onboardingService.completeTask(taskId);

        assertThat(task.isCompleted()).isTrue();
        assertThat(task.getCompletedAt()).isNotNull();
        verify(onboardingTaskRepository).save(task);
    }

    // ── 4 ────────────────────────────────────────────────────────────────────

    @Test
    void completeTask_alreadyCompleted_noChange() {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime original = OffsetDateTime.now().minusDays(1);
        OnboardingTask task = OnboardingTask.builder()
                .employeeId(employeeId).tenantId(tenantId)
                .taskName("Upload Documents").taskOrder(0)
                .completed(true).completedAt(original).build();
        when(onboardingTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        onboardingService.completeTask(taskId);

        assertThat(task.getCompletedAt()).isEqualTo(original);
        verify(onboardingTaskRepository, never()).save(any());
    }

    // ── 5 ────────────────────────────────────────────────────────────────────

    @Test
    void getTasksByEmployee_returnsAllTasks() {
        List<OnboardingTask> expected = List.of(
                OnboardingTask.builder().employeeId(employeeId).tenantId(tenantId)
                        .taskName("Task A").taskOrder(0).completed(false).build(),
                OnboardingTask.builder().employeeId(employeeId).tenantId(tenantId)
                        .taskName("Task B").taskOrder(1).completed(true).build());
        when(onboardingTaskRepository.findByEmployeeIdOrderByTaskOrderAsc(employeeId))
                .thenReturn(expected);

        List<OnboardingTask> result = onboardingService.getTasksByEmployee(employeeId);

        assertThat(result).isEqualTo(expected);
    }
}
