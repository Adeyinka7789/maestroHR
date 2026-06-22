package com.admtechhub.maestrohr.broadcast;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tracks that a single tenant user has read a {@link Broadcast} (Feature 5).
 *
 * <p>Global like {@link Broadcast} — no {@code tenant_id}, no {@code @SQLRestriction}, keyed by
 * {@code user_email}. Does <b>not</b> extend {@code BaseEntity}: the {@code broadcast_reads} table
 * has only a {@code read_at} timestamp (no {@code updated_at}), so the audit columns of
 * {@code BaseEntity} would not validate against the schema. The {@code (broadcast_id, user_email)}
 * unique constraint makes "mark as read" idempotent.
 */
@Entity
@Table(name = "broadcast_reads",
        uniqueConstraints = @UniqueConstraint(columnNames = {"broadcast_id", "user_email"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastRead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "broadcast_id", nullable = false)
    private UUID broadcastId;

    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    @CreationTimestamp
    @Column(name = "read_at", nullable = false, updatable = false)
    private OffsetDateTime readAt;
}
