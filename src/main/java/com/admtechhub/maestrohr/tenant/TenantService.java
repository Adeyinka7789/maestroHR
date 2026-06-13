package com.admtechhub.maestrohr.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;

    // Tenant provisioning (registration / admin create) is a cross-tenant write performed with
    // no tenant session, so it runs on the privileged datasource via
    // com.admtechhub.maestrohr.platform.TenantUserWrites — not through this scoped JPA service.

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

    @Transactional(readOnly = true)
    public long countAll(){
        return tenantRepository.count();
    }

    @Transactional(readOnly = true)
    public long countActive(){
        return tenantRepository.countActiveTenants();
    }
}