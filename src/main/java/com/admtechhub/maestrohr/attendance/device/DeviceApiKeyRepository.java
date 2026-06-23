package com.admtechhub.maestrohr.attendance.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeviceApiKeyRepository extends JpaRepository<DeviceApiKey, UUID> {

    List<DeviceApiKey> findAllByOrderByDeviceNameAsc();

    boolean existsByDeviceIdentifierAndTenantId(String deviceIdentifier, UUID tenantId);
}
