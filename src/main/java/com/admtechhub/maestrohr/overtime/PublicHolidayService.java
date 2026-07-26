package com.admtechhub.maestrohr.overtime;

import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the tenant's public-holiday calendar (see V66) and answers the "is this date a holiday?"
 * question for the overtime calculator. Tenant-scoped via RLS + {@code @SQLRestriction}.
 */
@Service
@RequiredArgsConstructor
public class PublicHolidayService {

    private final PublicHolidayRepository repository;

    @Transactional(readOnly = true)
    public List<PublicHoliday> list() {
        return repository.findAllByOrderByHolidayDateDesc();
    }

    @Transactional
    public PublicHoliday add(LocalDate date, String name) {
        if (date == null) {
            throw new IllegalArgumentException("A date is required.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A holiday name is required.");
        }
        if (repository.findByHolidayDate(date).isPresent()) {
            throw new IllegalArgumentException("A holiday is already set for " + date + ".");
        }
        return repository.save(PublicHoliday.builder()
                .tenantId(currentTenantId())
                .holidayDate(date)
                .name(name.trim())
                .active(true)
                .build());
    }

    @Transactional
    public void setActive(UUID id, boolean active) {
        PublicHoliday h = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Holiday not found."));
        h.setActive(active);
        repository.save(h);
    }

    @Transactional
    public void delete(UUID id) {
        repository.findById(id).ifPresent(repository::delete);
    }

    /** Active holiday dates within [from, to] — used by the overtime classification. */
    @Transactional(readOnly = true)
    public Set<LocalDate> activeDatesBetween(LocalDate from, LocalDate to) {
        Set<LocalDate> dates = new HashSet<>();
        for (PublicHoliday h : repository.findByActiveTrueAndHolidayDateBetween(from, to)) {
            dates.add(h.getHolidayDate());
        }
        return dates;
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
