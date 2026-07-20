package com.admtechhub.maestrohr.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Resolves and manages admin-configured subscription {@link Discount}s.
 *
 * <p>Discounts are GLOBAL platform config (no tenant scoping / RLS); see {@link Discount}.
 * At checkout, {@link #resolveBestDiscount} picks the single most favourable applicable
 * discount — they do not stack. All CRUD here is SUPER_ADMIN-only at the controller layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscountService {

    private static final int BPS_DENOMINATOR = 10_000; // 100% expressed in basis points

    private final DiscountRepository discountRepository;

    /**
     * Compute the best (lowest-net) discount for a {@code planName}/{@code period} purchase by
     * {@code tenantId} against {@code baseKobo}. Never returns {@code null}: when nothing applies
     * it returns {@link AppliedDiscount#none}. A non-positive base (e.g. FREE_TRIAL) is never
     * discounted.
     */
    @Transactional(readOnly = true)
    public AppliedDiscount resolveBestDiscount(UUID tenantId, String planName, String period, long baseKobo) {
        if (baseKobo <= 0) {
            return AppliedDiscount.none(baseKobo);
        }

        List<Discount> applicable =
                discountRepository.findApplicable(tenantId, planName, period, OffsetDateTime.now());

        AppliedDiscount best = AppliedDiscount.none(baseKobo);
        for (Discount d : applicable) {
            long off = discountAmountKobo(d, baseKobo);
            if (off <= 0) {
                continue;
            }
            long net = Math.max(0, baseKobo - off);
            if (net < best.netKobo()) {
                best = new AppliedDiscount(d.getId(), d.getLabel(), baseKobo, baseKobo - net, net);
            }
        }

        if (best.hasDiscount()) {
            log.info("Discount '{}' applied for tenant {} {}/{}: {} → {} kobo",
                    best.label(), tenantId, planName, period, baseKobo, best.netKobo());
        }
        return best;
    }

    /** Kobo taken off {@code baseKobo} by a single discount, clamped to [0, baseKobo]. */
    private long discountAmountKobo(Discount d, long baseKobo) {
        long off = switch (d.getDiscountType()) {
            case PERCENTAGE -> d.getPercentBps() == null
                    ? 0L
                    : baseKobo * d.getPercentBps() / BPS_DENOMINATOR;
            case FIXED -> d.getAmountKobo() == null ? 0L : d.getAmountKobo();
        };
        return Math.max(0, Math.min(off, baseKobo));
    }

    // ── Admin CRUD ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Discount> listAll() {
        return discountRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public Discount create(Discount discount) {
        validate(discount);
        Discount saved = discountRepository.save(discount);
        log.info("Discount created: id={}, label='{}', type={}", saved.getId(), saved.getLabel(), saved.getDiscountType());
        return saved;
    }

    @Transactional
    public Discount update(UUID id, Discount changes) {
        Discount existing = discountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Discount not found: " + id));
        existing.setLabel(changes.getLabel());
        existing.setDiscountType(changes.getDiscountType());
        existing.setPercentBps(changes.getPercentBps());
        existing.setAmountKobo(changes.getAmountKobo());
        existing.setTenantId(changes.getTenantId());
        existing.setPlanName(changes.getPlanName());
        existing.setPeriod(changes.getPeriod());
        existing.setStartsAt(changes.getStartsAt());
        existing.setEndsAt(changes.getEndsAt());
        existing.setIsActive(changes.getIsActive());
        validate(existing);
        log.info("Discount updated: id={}", id);
        return discountRepository.save(existing);
    }

    @Transactional
    public void setActive(UUID id, boolean active) {
        Discount existing = discountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Discount not found: " + id));
        existing.setIsActive(active);
        discountRepository.save(existing);
        log.info("Discount {} set active={}", id, active);
    }

    @Transactional
    public void delete(UUID id) {
        discountRepository.deleteById(id);
        log.info("Discount deleted: id={}", id);
    }

    /**
     * Server-side guard mirroring the DB CHECK constraints, so bad input fails as a clean 400
     * (handled by GlobalExceptionHandler) rather than a raw constraint-violation 500.
     */
    private void validate(Discount d) {
        if (d.getLabel() == null || d.getLabel().isBlank()) {
            throw new IllegalArgumentException("Discount label is required");
        }
        if (d.getDiscountType() == null) {
            throw new IllegalArgumentException("Discount type is required");
        }
        switch (d.getDiscountType()) {
            case PERCENTAGE -> {
                if (d.getPercentBps() == null || d.getPercentBps() <= 0 || d.getPercentBps() > BPS_DENOMINATOR) {
                    throw new IllegalArgumentException("Percentage must be between 0 and 100");
                }
                d.setAmountKobo(null);
            }
            case FIXED -> {
                if (d.getAmountKobo() == null || d.getAmountKobo() <= 0) {
                    throw new IllegalArgumentException("Fixed discount amount must be greater than 0");
                }
                d.setPercentBps(null);
            }
        }
        if (d.getStartsAt() != null && d.getEndsAt() != null && d.getEndsAt().isBefore(d.getStartsAt())) {
            throw new IllegalArgumentException("End date must be on or after the start date");
        }
    }
}
