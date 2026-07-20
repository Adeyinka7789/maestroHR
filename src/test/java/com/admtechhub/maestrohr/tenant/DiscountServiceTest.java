package com.admtechhub.maestrohr.tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscountServiceTest {

    @Mock private DiscountRepository discountRepository;
    @InjectMocks private DiscountService service;

    private static final UUID TENANT = UUID.randomUUID();
    private static final long BASE = 100_000L; // ₦1,000

    private Discount percentage(int bps) {
        return Discount.builder().label(bps / 100 + "% off").discountType(DiscountType.PERCENTAGE)
                .percentBps(bps).isActive(true).build();
    }

    private Discount fixed(long kobo) {
        return Discount.builder().label("₦" + kobo / 100 + " off").discountType(DiscountType.FIXED)
                .amountKobo(kobo).isActive(true).build();
    }

    private void stub(List<Discount> discounts) {
        when(discountRepository.findApplicable(eq(TENANT), eq("PROFESSIONAL"), eq("MONTHLY"), any(OffsetDateTime.class)))
                .thenReturn(discounts);
    }

    @Test
    void noApplicableDiscount_returnsNone() {
        stub(List.of());
        AppliedDiscount r = service.resolveBestDiscount(TENANT, "PROFESSIONAL", "MONTHLY", BASE);
        assertThat(r.hasDiscount()).isFalse();
        assertThat(r.netKobo()).isEqualTo(BASE);
        assertThat(r.discountKobo()).isZero();
    }

    @Test
    void percentageDiscount_computesNet() {
        stub(List.of(percentage(2000))); // 20%
        AppliedDiscount r = service.resolveBestDiscount(TENANT, "PROFESSIONAL", "MONTHLY", BASE);
        assertThat(r.discountKobo()).isEqualTo(20_000L);
        assertThat(r.netKobo()).isEqualTo(80_000L);
    }

    @Test
    void fixedDiscount_computesNet() {
        stub(List.of(fixed(15_000L)));
        AppliedDiscount r = service.resolveBestDiscount(TENANT, "PROFESSIONAL", "MONTHLY", BASE);
        assertThat(r.discountKobo()).isEqualTo(15_000L);
        assertThat(r.netKobo()).isEqualTo(85_000L);
    }

    @Test
    void multipleApplicable_bestForCustomerWins() {
        // 20% (=₦200 off) vs ₦500 fixed off → fixed is better (lower net).
        stub(List.of(percentage(2000), fixed(50_000L)));
        AppliedDiscount r = service.resolveBestDiscount(TENANT, "PROFESSIONAL", "MONTHLY", BASE);
        assertThat(r.netKobo()).isEqualTo(50_000L);
        assertThat(r.discountKobo()).isEqualTo(50_000L);
    }

    @Test
    void fixedGreaterThanBase_clampsNetToZero() {
        stub(List.of(fixed(500_000L)));
        AppliedDiscount r = service.resolveBestDiscount(TENANT, "PROFESSIONAL", "MONTHLY", BASE);
        assertThat(r.netKobo()).isZero();
        assertThat(r.discountKobo()).isEqualTo(BASE);
    }

    @Test
    void hundredPercent_zeroesNet() {
        stub(List.of(percentage(10_000)));
        AppliedDiscount r = service.resolveBestDiscount(TENANT, "PROFESSIONAL", "MONTHLY", BASE);
        assertThat(r.netKobo()).isZero();
    }

    @Test
    void freeBasePrice_neverDiscounted() {
        // baseKobo <= 0 short-circuits before any repository lookup.
        AppliedDiscount r = service.resolveBestDiscount(TENANT, "FREE_TRIAL", "MONTHLY", 0L);
        assertThat(r.hasDiscount()).isFalse();
        assertThat(r.netKobo()).isZero();
    }
}
