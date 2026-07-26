package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.gl.GlDtos.CostCenterForm;
import com.admtechhub.maestrohr.gl.GlDtos.CostCenterView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the tenant's {@link CostCenter} catalogue — the branches / accounting units employees are
 * tagged to for GL attribution. Mirrors the shape of the other small tenant-config services;
 * tenant isolation is enforced by RLS + {@code @SQLRestriction}.
 */
@Service
@RequiredArgsConstructor
public class CostCenterService {

    private final CostCenterRepository costCenterRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<CostCenterView> list() {
        Map<UUID, Long> counts = employeeCounts();
        return costCenterRepository.findAllByOrderByNameAsc().stream()
                .map(cc -> toView(cc, counts.getOrDefault(cc.getId(), 0L)))
                .toList();
    }

    /** Active cost centers only — for the employee form dropdown. */
    @Transactional(readOnly = true)
    public List<CostCenterView> listActive() {
        return costCenterRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(cc -> toView(cc, 0L))
                .toList();
    }

    @Transactional
    public CostCenterView create(CostCenterForm form) {
        String name = requireText(form.name(), "Cost center name is required.");
        // normalizeCode guarantees uniqueness within the tenant (auto-suffixes a collision).
        String code = normalizeCode(form.code(), name);
        CostCenter cc = CostCenter.builder()
                .tenantId(currentTenantId())
                .name(name)
                .code(code)
                .location(trimToNull(form.location()))
                .glAccountCode(trimToNull(form.glAccountCode()))
                .active(true)
                .build();
        return toView(costCenterRepository.save(cc), 0L);
    }

    @Transactional
    public CostCenterView update(UUID id, CostCenterForm form) {
        CostCenter cc = costCenterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cost center not found."));
        cc.setName(requireText(form.name(), "Cost center name is required."));
        cc.setLocation(trimToNull(form.location()));
        cc.setGlAccountCode(trimToNull(form.glAccountCode()));
        return toView(costCenterRepository.save(cc), employeeCounts().getOrDefault(id, 0L));
    }

    @Transactional
    public void setActive(UUID id, boolean active) {
        CostCenter cc = costCenterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cost center not found."));
        cc.setActive(active);
        costCenterRepository.save(cc);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Map<UUID, Long> employeeCounts() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : employeeRepository.countByCostCenter()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private CostCenterView toView(CostCenter cc, long employeeCount) {
        return new CostCenterView(cc.getId(), cc.getName(), cc.getCode(), cc.getLocation(),
                cc.getGlAccountCode(), cc.isActive(), employeeCount);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Use the supplied code, else derive one from the name; upper snake, unique-suffixed. */
    private String normalizeCode(String rawCode, String name) {
        String base = (rawCode != null && !rawCode.isBlank() ? rawCode : name)
                .toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("(^_+)|(_+$)", "");
        if (base.isBlank()) {
            base = "CC";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        String code = base;
        int n = 2;
        while (costCenterRepository.existsByCode(code)) {
            code = base + "_" + n++;
        }
        return code;
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
