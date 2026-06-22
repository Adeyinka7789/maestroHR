package com.admtechhub.maestrohr.broadcast;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for platform broadcasts (Feature 5).
 *
 * <p>Pure JPA on the primary datasource: the broadcast tables carry no RLS policy (V28), so no
 * privileged datasource or tenant-session binding is needed. Plan-tier targeting is resolved here
 * because {@code target_plans} is a comma-separated string ({@code "ALL"} or e.g.
 * {@code "BASIC,PROFESSIONAL"}) that is simpler to match in Java than in JPQL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastService {

    /** Valid single targets offered by the compose UI. {@code ALL} means every tenant. */
    public static final String TARGET_ALL = "ALL";

    private final BroadcastRepository broadcastRepository;
    private final BroadcastReadRepository broadcastReadRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public Broadcast create(String title, String body, String targetPlans, String createdBy) {
        return broadcastRepository.save(Broadcast.builder()
                .title(title.trim())
                .body(body.trim())
                .targetPlans(normalizeTarget(targetPlans))
                .createdBy(createdBy)
                .build());
    }

    /** All broadcasts, newest first, each with its read count — for the superadmin "sent" table. */
    @Transactional(readOnly = true)
    public List<BroadcastSummary> listAllWithReadCounts() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : broadcastRepository.countReadsByBroadcast()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return broadcastRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(b -> new BroadcastSummary(
                        b.getId(), b.getTitle(), b.getBody(), b.getTargetPlans(),
                        b.getCreatedBy(), b.getCreatedAt(),
                        counts.getOrDefault(b.getId(), 0L)))
                .toList();
    }

    /**
     * Unread broadcasts for {@code email}, filtered to the caller's current tenant plan. The plan
     * is read from the tenant bound in {@link TenantContext} for this request; a broadcast is shown
     * when it targets {@code ALL} or explicitly lists that plan.
     */
    @Transactional(readOnly = true)
    public List<UnreadBroadcast> unreadFor(String email) {
        SubscriptionPlan plan = currentTenantPlan();
        return broadcastRepository.findUnreadFor(email).stream()
                .filter(b -> targets(b.getTargetPlans(), plan))
                .map(b -> new UnreadBroadcast(b.getId(), b.getTitle(), b.getBody(), b.getCreatedAt()))
                .toList();
    }

    /** Mark a broadcast read for {@code email}; idempotent against the unique constraint. */
    @Transactional
    public void markRead(UUID broadcastId, String email) {
        if (broadcastReadRepository.existsByBroadcastIdAndUserEmail(broadcastId, email)) {
            return;
        }
        try {
            broadcastReadRepository.save(BroadcastRead.builder()
                    .broadcastId(broadcastId)
                    .userEmail(email)
                    .build());
        } catch (DataIntegrityViolationException raced) {
            // Concurrent dismiss from another tab already inserted the row — that's the desired end state.
            log.debug("Broadcast {} already marked read for {}", broadcastId, email);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private boolean targets(String targetPlans, SubscriptionPlan plan) {
        if (targetPlans == null || targetPlans.isBlank() || TARGET_ALL.equalsIgnoreCase(targetPlans.trim())) {
            return true;
        }
        if (plan == null) {
            return false;
        }
        return Arrays.stream(targetPlans.split(","))
                .map(String::trim)
                .anyMatch(p -> p.equalsIgnoreCase(plan.name()));
    }

    /**
     * Normalize the compose-form target into either {@code "ALL"} or a comma-separated list of
     * valid {@link SubscriptionPlan} names. Unknown tokens are dropped; if nothing valid remains
     * the broadcast falls back to {@code "ALL"}.
     */
    private String normalizeTarget(String raw) {
        if (raw == null || raw.isBlank() || TARGET_ALL.equalsIgnoreCase(raw.trim())) {
            return TARGET_ALL;
        }
        Set<String> valid = Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(BroadcastService::isPlan)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return valid.isEmpty() ? TARGET_ALL : String.join(",", valid);
    }

    private static boolean isPlan(String token) {
        return Arrays.stream(SubscriptionPlan.values()).anyMatch(p -> p.name().equals(token));
    }

    private SubscriptionPlan currentTenantPlan() {
        String tenant = TenantContext.getCurrentTenant();
        if (tenant == null || tenant.isBlank()) {
            return null;
        }
        try {
            return tenantRepository.findById(UUID.fromString(tenant))
                    .map(Tenant::getSubscriptionPlan)
                    .orElse(null);
        } catch (IllegalArgumentException badUuid) {
            return null;
        }
    }

    /** Superadmin "sent" table row. */
    public record BroadcastSummary(
            UUID id,
            String title,
            String body,
            String targetPlans,
            String createdBy,
            OffsetDateTime createdAt,
            long readCount) {
    }

    /** Tenant-facing unread broadcast (no author/target leak). */
    public record UnreadBroadcast(
            UUID id,
            String title,
            String body,
            OffsetDateTime createdAt) {
    }
}
