package com.admtechhub.maestrohr.performance;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.tenant.Tenant;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "review_cycles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ReviewCycle extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private ReviewTemplate template;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private Employee reviewer;

    @Column(name = "review_period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "review_period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "status")
    private String status;

    @Column(name = "self_review_status")
    private String selfReviewStatus;

    @Column(name = "manager_review_status")
    private String managerReviewStatus;

    @Column(name = "overall_rating")
    private BigDecimal overallRating;

    @Column(name = "created_by")
    private String createdBy;
}