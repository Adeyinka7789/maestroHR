package com.admtechhub.maestrohr.attendance.device;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceApiKeyService {

    private final DeviceApiKeyRepository deviceApiKeyRepository;
    private final DeviceEmployeeEnrollmentRepository enrollmentRepository;
    private final DeviceSyncErrorRepository syncErrorRepository;
    private final TenantRepository tenantRepository;
    private final EmployeeRepository employeeRepository;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── Key management ────────────────────────────────────────────────────────

    @Transactional
    public DeviceKeyCreatedDTO createKey(CreateDeviceApiKeyRequest req) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        String rawKey = generateRawKey();
        String hash = sha256Hex(rawKey);

        DeviceApiKey key = DeviceApiKey.builder()
                .tenant(tenant)
                .deviceName(req.getDeviceName())
                .deviceIdentifier(req.getDeviceIdentifier())
                .location(req.getLocation())
                .apiKeyHash(hash)
                .isActive(true)
                .build();

        DeviceApiKey saved = deviceApiKeyRepository.save(key);
        log.info("Device API key created: id={} name={} tenant={}", saved.getId(), saved.getDeviceName(), tenantId);

        return new DeviceKeyCreatedDTO(saved.getId(), saved.getDeviceName(), rawKey, saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<DeviceApiKeyDTO> listKeys() {
        return deviceApiKeyRepository.findAllByOrderByDeviceNameAsc()
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void revokeKey(UUID keyId) {
        DeviceApiKey key = deviceApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("Device API key not found"));
        key.setActive(false);
        deviceApiKeyRepository.save(key);
        log.info("Device API key revoked: id={}", keyId);
    }

    @Transactional
    public void deleteKey(UUID keyId) {
        DeviceApiKey key = deviceApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("Device API key not found"));
        deviceApiKeyRepository.delete(key);
        log.info("Device API key deleted: id={}", keyId);
    }

    // ── Enrollment management ─────────────────────────────────────────────────

    @Transactional
    public DeviceEnrollmentDTO createEnrollment(CreateEnrollmentRequest req) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        DeviceApiKey device = deviceApiKeyRepository.findById(req.getDeviceApiKeyId())
                .orElseThrow(() -> new IllegalArgumentException("Device not found"));

        Employee employee = employeeRepository.findById(req.getEmployeeId())
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        boolean duplicate = enrollmentRepository
                .findByDeviceApiKeyIdAndDeviceEmployeeId(device.getId(), req.getDeviceEmployeeId())
                .isPresent();
        if (duplicate) {
            throw new IllegalStateException(
                    "Employee ID '" + req.getDeviceEmployeeId() + "' is already enrolled on this device");
        }

        DeviceEmployeeEnrollment enrollment = DeviceEmployeeEnrollment.builder()
                .tenant(tenant)
                .deviceApiKey(device)
                .deviceEmployeeId(req.getDeviceEmployeeId())
                .employee(employee)
                .build();

        DeviceEmployeeEnrollment saved = enrollmentRepository.save(enrollment);
        log.info("Device enrollment created: device={} deviceEmpId={} employee={}", device.getId(), req.getDeviceEmployeeId(), employee.getId());
        return toEnrollmentDto(saved);
    }

    @Transactional(readOnly = true)
    public List<DeviceEnrollmentDTO> listEnrollments(UUID deviceApiKeyId) {
        return enrollmentRepository.findAllByDeviceApiKeyId(deviceApiKeyId)
                .stream()
                .map(this::toEnrollmentDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteEnrollment(UUID enrollmentId) {
        DeviceEmployeeEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found"));
        enrollmentRepository.delete(enrollment);
        log.info("Device enrollment deleted: id={}", enrollmentId);
    }

    // ── Sync error management ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DeviceSyncErrorDTO> listUnresolvedErrors(int limit) {
        return syncErrorRepository
                .findAllByResolvedOrderByCreatedAtDesc(false,
                        org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(this::toSyncErrorDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void resolveError(UUID errorId) {
        DeviceSyncError error = syncErrorRepository.findById(errorId)
                .orElseThrow(() -> new IllegalArgumentException("Sync error not found"));
        error.setResolved(true);
        error.setResolvedAt(java.time.OffsetDateTime.now());
        syncErrorRepository.save(error);
    }

    public long countUnresolvedErrors() {
        return syncErrorRepository.countByResolved(false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DeviceApiKeyDTO toDto(DeviceApiKey k) {
        return new DeviceApiKeyDTO(k.getId(), k.getDeviceName(), k.getDeviceIdentifier(),
                k.getLocation(), k.isActive(), k.getLastUsedAt(), k.getCreatedAt());
    }

    private DeviceEnrollmentDTO toEnrollmentDto(DeviceEmployeeEnrollment e) {
        return new DeviceEnrollmentDTO(
                e.getId(),
                e.getDeviceApiKey().getId(),
                e.getDeviceApiKey().getDeviceName(),
                e.getDeviceEmployeeId(),
                e.getEmployee().getId(),
                e.getEmployee().getFullName(),
                e.getEnrolledAt());
    }

    private DeviceSyncErrorDTO toSyncErrorDto(DeviceSyncError e) {
        String deviceName = e.getDeviceApiKey() != null ? e.getDeviceApiKey().getDeviceName() : "unknown";
        return new DeviceSyncErrorDTO(e.getId(), deviceName, e.getDeviceEmployeeId(),
                e.getDeviceIdentifier(), e.getEventType(), e.getEventTimestamp(),
                e.getErrorReason(), e.isResolved(), e.getResolvedAt(), e.getCreatedAt());
    }

    private static String generateRawKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "mhrdev_" + HexFormat.of().formatHex(bytes);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
