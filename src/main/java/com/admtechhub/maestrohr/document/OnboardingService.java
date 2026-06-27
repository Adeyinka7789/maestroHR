package com.admtechhub.maestrohr.document;

import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Onboarding checklist for new hires. When an employee is created with status=ONBOARDING,
 * {@code EmployeeService} calls {@link #createDefaultTasksForEmployee}; the employee then ticks
 * items off from their dashboard. Tenant isolation is via RLS + the entity {@code @SQLRestriction}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingService {

    /** Starter checklist seeded for every new hire, in display order. */
    static final List<String> DEFAULT_TASKS = List.of(
            "Review Company Handbook",
            "Confirm Personal Details",
            "Upload Required Documents",
            "Verify Bank Details",
            "Complete Profile Photo"
    );

    private final OnboardingTaskRepository onboardingTaskRepository;

    /**
     * Seed the default checklist for an employee. Idempotent: a no-op if any task already exists
     * for them, so re-running employee creation / status flips never duplicates the list.
     */
    @Transactional
    public void createDefaultTasksForEmployee(UUID employeeId) {
        if (onboardingTaskRepository.existsByEmployeeId(employeeId)) {
            log.debug("Onboarding tasks already exist for employee {}; skipping seed", employeeId);
            return;
        }

        UUID tenantId = currentTenantId();
        List<OnboardingTask> tasks = new ArrayList<>(DEFAULT_TASKS.size());
        for (int i = 0; i < DEFAULT_TASKS.size(); i++) {
            tasks.add(OnboardingTask.builder()
                    .tenantId(tenantId)
                    .employeeId(employeeId)
                    .taskName(DEFAULT_TASKS.get(i))
                    .taskOrder(i)
                    .completed(false)
                    .build());
        }
        onboardingTaskRepository.saveAll(tasks);
        log.info("Seeded {} onboarding task(s) for employee {}", tasks.size(), employeeId);
    }

    /** The ordered checklist for an employee. */
    @Transactional(readOnly = true)
    public List<OnboardingTask> getTasksByEmployee(UUID employeeId) {
        return onboardingTaskRepository.findByEmployeeIdOrderByTaskOrderAsc(employeeId);
    }

    /** Mark a task complete (idempotent on an already-complete task). */
    @Transactional
    public void completeTask(UUID taskId) {
        OnboardingTask task = onboardingTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Onboarding task not found: " + taskId));
        if (!task.isCompleted()) {
            task.setCompleted(true);
            task.setCompletedAt(OffsetDateTime.now());
            onboardingTaskRepository.save(task);
        }
    }

    /** Outstanding (unticked) task count — drives whether the dashboard checklist still shows. */
    @Transactional(readOnly = true)
    public long outstandingCount(UUID employeeId) {
        return onboardingTaskRepository.countByEmployeeIdAndCompletedFalse(employeeId);
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
