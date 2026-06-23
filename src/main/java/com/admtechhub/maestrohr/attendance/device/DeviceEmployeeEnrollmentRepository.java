package com.admtechhub.maestrohr.attendance.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceEmployeeEnrollmentRepository extends JpaRepository<DeviceEmployeeEnrollment, UUID> {

    Optional<DeviceEmployeeEnrollment> findByDeviceApiKeyIdAndDeviceEmployeeId(
            UUID deviceApiKeyId, String deviceEmployeeId);

    List<DeviceEmployeeEnrollment> findAllByDeviceApiKeyId(UUID deviceApiKeyId);

    List<DeviceEmployeeEnrollment> findAllByEmployeeId(UUID employeeId);

    void deleteByDeviceApiKeyIdAndDeviceEmployeeId(UUID deviceApiKeyId, String deviceEmployeeId);
}
