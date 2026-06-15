package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.DepartmentDTO;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link DepartmentListView} for the redesigned
 * departments page. A single {@link DepartmentRepository#findFilteredWithEmployeeCount}
 * call backs the list and its optional name search, so the table renders fully
 * populated with no client-side fetches. Mirrors {@link EmployeeListService}.
 */
@Service
@RequiredArgsConstructor
public class DepartmentListService {

    private static final DateTimeFormatter CREATED_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    private final DepartmentRepository departmentRepository;

    @Transactional(readOnly = true)
    public DepartmentListView buildList(String search) {
        UUID tenantId = currentTenantId();
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();

        List<DepartmentDTO> results =
                departmentRepository.findFilteredWithEmployeeCount(tenantId, normalizedSearch);

        List<DepartmentListView.Row> rows = results.stream().map(this::toRow).toList();

        return new DepartmentListView(rows, rows.size(), normalizedSearch);
    }

    private DepartmentListView.Row toRow(DepartmentDTO d) {
        return new DepartmentListView.Row(
                d.getId(),
                d.getName(),
                d.getEmployeeCount() != null ? d.getEmployeeCount() : 0L,
                formatCreated(d.getCreatedAt()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String formatCreated(OffsetDateTime createdAt) {
        return createdAt == null ? "—" : createdAt.format(CREATED_FORMAT);
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
