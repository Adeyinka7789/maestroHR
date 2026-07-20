package com.admtechhub.maestrohr.admin;

import com.admtechhub.maestrohr.admin.dto.DiscountRequest;
import com.admtechhub.maestrohr.admin.dto.DiscountResponse;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import com.admtechhub.maestrohr.tenant.Discount;
import com.admtechhub.maestrohr.tenant.DiscountService;
import com.admtechhub.maestrohr.tenant.DiscountType;
import com.admtechhub.maestrohr.tenant.TenantWithUserCountDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SUPER_ADMIN CRUD for subscription discounts (V58). Discounts are global platform config
 * (see {@link Discount}); management is gated to SUPER_ADMIN both here (method security) and
 * at the URL layer ({@code /htmx/admin/**} in SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/discounts")
@RequiredArgsConstructor
@Slf4j
public class AdminDiscountController {

    private final DiscountService discountService;
    private final AdminStatsQueries adminStatsQueries;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<DiscountResponse>>> list() {
        Map<UUID, String> tenantNames = tenantNameMap();
        List<DiscountResponse> out = discountService.listAll().stream()
                .map(d -> DiscountResponse.from(d, tenantNames))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("Discounts retrieved", out));
    }

    /** Companies for the "specific customer" selector in the admin form. */
    @GetMapping("/tenants")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> tenants() {
        List<Map<String, String>> out = adminStatsQueries.findAllTenantsWithUserCount().stream()
                .map(t -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("id", t.getId().toString());
                    m.put("companyName", t.getCompanyName());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("Tenants retrieved", out));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<DiscountResponse>> create(@RequestBody DiscountRequest request) {
        Discount saved = discountService.create(toEntity(request));
        return ResponseEntity.ok(ApiResponse.success("Discount created", DiscountResponse.from(saved, tenantNameMap())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<DiscountResponse>> update(
            @PathVariable UUID id, @RequestBody DiscountRequest request) {
        Discount saved = discountService.update(id, toEntity(request));
        return ResponseEntity.ok(ApiResponse.success("Discount updated", DiscountResponse.from(saved, tenantNameMap())));
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> toggle(@PathVariable UUID id, @RequestParam boolean active) {
        discountService.setActive(id, active);
        return ResponseEntity.ok(ApiResponse.success("Discount " + (active ? "activated" : "deactivated"), null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        discountService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Discount deleted", null));
    }

    // ── mapping helpers ───────────────────────────────────────────────────────

    private Discount toEntity(DiscountRequest r) {
        if (r.getDiscountType() == null || r.getDiscountType().isBlank()) {
            throw new IllegalArgumentException("Discount type is required");
        }
        DiscountType type = DiscountType.valueOf(r.getDiscountType().toUpperCase());

        Discount d = Discount.builder()
                .label(r.getLabel() != null ? r.getLabel().trim() : null)
                .discountType(type)
                .tenantId(r.getTenantId())
                .planName(blankToNull(r.getPlanName()))
                .period(blankToNull(r.getPeriod()))
                .startsAt(parseDayStart(r.getStartDate()))
                .endsAt(parseDayEnd(r.getEndDate()))
                .isActive(r.getIsActive() == null ? Boolean.TRUE : r.getIsActive())
                .build();

        if (type == DiscountType.PERCENTAGE) {
            d.setPercentBps(r.getPercent() == null ? null : (int) Math.round(r.getPercent() * 100));
        } else {
            d.setAmountKobo(r.getAmountNaira() == null ? null : Math.round(r.getAmountNaira() * 100));
        }
        return d;
    }

    private Map<UUID, String> tenantNameMap() {
        return adminStatsQueries.findAllTenantsWithUserCount().stream()
                .collect(Collectors.toMap(TenantWithUserCountDTO::getId, TenantWithUserCountDTO::getCompanyName, (a, b) -> a));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase();
    }

    private static OffsetDateTime parseDayStart(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        return LocalDate.parse(isoDate.trim()).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static OffsetDateTime parseDayEnd(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        return LocalDate.parse(isoDate.trim()).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
