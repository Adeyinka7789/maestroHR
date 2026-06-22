package com.admtechhub.maestrohr.broadcast;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * A platform-wide announcement authored by a SUPER_ADMIN (Feature 5).
 *
 * <p>Deliberately <b>global</b>: no {@code tenant_id} and no {@code @SQLRestriction}. The
 * {@code broadcasts} table carries no RLS policy (see V28), so this entity is read and written
 * through the primary datasource exactly like any other JPA entity — there is nothing for RLS
 * to scope it to.
 *
 * <p>{@code targetPlans} is either the literal {@code "ALL"} or a comma-separated list of
 * {@link com.admtechhub.maestrohr.tenant.SubscriptionPlan} names (e.g. {@code "BASIC,PROFESSIONAL"}).
 */
@Entity
@Table(name = "broadcasts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class Broadcast extends BaseEntity {

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "target_plans", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String targetPlans = "ALL";

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;
}
