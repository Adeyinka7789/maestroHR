package com.admtechhub.maestrohr.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One item on a new hire's onboarding checklist (see V34). Seeded per-employee by
 * {@code OnboardingService.createDefaultTasksForEmployee} when an employee is created with
 * status=ONBOARDING; the employee ticks items off from their dashboard.
 *
 * <p>Does not extend {@code BaseEntity}: the {@code employee_onboarding_tasks} table is
 * append-then-flip-once (created, later marked complete) and carries no {@code updated_at}
 * column, so the entity defines only its own id and {@code created_at}.
 */
@Entity
@Table(name = "employee_onboarding_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class OnboardingTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "task_name", nullable = false, length = 255)
    private String taskName;

    @Column(name = "task_order", nullable = false)
    private int taskOrder;

    @Column(name = "is_completed", nullable = false)
    @Builder.Default
    private boolean completed = false;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
