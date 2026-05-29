package com.admtechhub.maestrohr.exit;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "final_settlements")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class FinalSettlement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exit_request_id", nullable = false)
    private ExitRequest exitRequest;

    private BigDecimal unpaidSalary;
    private BigDecimal accruedLeave;
    private BigDecimal severancePay;
    private BigDecimal otherDeductions;
    private BigDecimal totalPayable;
    private String paymentStatus;   // PENDING, PAID
    private LocalDate paymentDate;
    private String notes;
}