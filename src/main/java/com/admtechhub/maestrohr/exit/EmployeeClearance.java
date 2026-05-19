package com.admtechhub.maestrohr.exit;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "employee_clearance")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class EmployeeClearance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exit_request_id", nullable = false)
    private ExitRequest exitRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clearance_item_id", nullable = false)
    private ClearanceItem clearanceItem;

    private String status;   // PENDING, CLEARED
    private String clearedBy;
    private OffsetDateTime clearedAt;
    private String notes;
}