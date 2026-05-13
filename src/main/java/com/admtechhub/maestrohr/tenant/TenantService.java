package com.admtechhub.maestrohr.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;

    @Transactional
    public Tenant registerTenant(String companyName, String rcNumber,
                                 String industry, String companySize) {

        if (rcNumber != null && tenantRepository.existsByRcNumber(rcNumber)) {
            throw new IllegalArgumentException(
                    "A company with this RC number is already registered"
            );
        }

        Tenant tenant = Tenant.builder()
                .companyName(companyName)
                .rcNumber(rcNumber)
                .industry(industry)
                .companySize(companySize)
                .subscriptionPlan(SubscriptionPlan.FREE_TRIAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .isActive(true)
                .build();

        Tenant saved = tenantRepository.save(tenant);
        log.info("New tenant registered: {} with id: {}",
                companyName, saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Tenant findById(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tenant not found"
                ));
    }

    @Transactional
    public void deactivateTenant(UUID id) {
        Tenant tenant = findById(id);
        tenant.setActive(false);
        tenantRepository.save(tenant);
        log.info("Tenant deactivated: {}", id);
    }
}