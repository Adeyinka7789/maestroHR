package com.admtechhub.maestrohr.training;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrainingService {

    private final TrainingProgramRepository trainingProgramRepository;
    private final EmployeeTrainingRepository employeeTrainingRepository;
    private final CertificationRepository certificationRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;

    // Helper to convert OffsetDateTime to LocalDateTime
    private LocalDateTime toLocalDateTime(OffsetDateTime odt) {
        return odt != null ? odt.toLocalDateTime() : null;
    }

    // ==================== DTO CONVERSIONS ====================

    private TrainingProgramDTO toDto(TrainingProgram program) {
        if (program == null) return null;
        return TrainingProgramDTO.builder()
                .id(program.getId())
                .title(program.getTitle())
                .description(program.getDescription())
                .category(program.getCategory())
                .durationHours(program.getDurationHours())
                .trainerName(program.getTrainerName())
                .trainerEmail(program.getTrainerEmail())
                .maxParticipants(program.getMaxParticipants())
                .cost(program.getCost())
                .status(program.getStatus())
                .createdAt(toLocalDateTime(program.getCreatedAt()))
                .build();
    }

    private CertificationDTO toDto(Certification cert) {
        if (cert == null) return null;
        Employee emp = cert.getEmployee();
        return CertificationDTO.builder()
                .id(cert.getId())
                .employeeId(emp != null ? emp.getId() : null)
                .employeeName(emp != null ? emp.getFullName() : null)
                .employeeNumber(emp != null ? emp.getEmployeeNumber() : null)
                .name(cert.getName())
                .issuingBody(cert.getIssuingBody())
                .issueDate(cert.getIssueDate())
                .expiryDate(cert.getExpiryDate())
                .certificateUrl(cert.getCertificateUrl())
                .reminderSent(cert.getReminderSent())
                .status(cert.getStatus())
                .createdAt(toLocalDateTime(cert.getCreatedAt()))
                .build();
    }

    private UUID getCurrentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }

    // ==================== TRAINING PROGRAMS (DTO) ====================

    @Transactional
    public TrainingProgramDTO createTrainingProgram(TrainingProgram program) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        program.setTenant(tenant);
        program.setStatus("ACTIVE");
        TrainingProgram saved = trainingProgramRepository.save(program);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<TrainingProgramDTO> getTrainingPrograms(Pageable pageable) {
        Page<TrainingProgram> page = trainingProgramRepository.findByTenantId(getCurrentTenantId(), pageable);
        return page.map(this::toDto);
    }

    @Transactional
    public void deleteTrainingProgram(UUID id) {
        trainingProgramRepository.deleteById(id);
    }

    // ==================== EMPLOYEE ENROLLMENTS ====================

    @Transactional
    public EmployeeTraining enrollEmployee(UUID employeeId, UUID trainingId) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
        TrainingProgram training = trainingProgramRepository.findById(trainingId)
                .orElseThrow(() -> new IllegalArgumentException("Training not found"));

        EmployeeTraining enrollment = EmployeeTraining.builder()
                .tenant(tenant)
                .employee(employee)
                .training(training)
                .enrollmentDate(LocalDate.now())
                .status("ENROLLED")
                .build();

        return employeeTrainingRepository.save(enrollment);
    }

    @Transactional
    public EmployeeTraining updateTrainingStatus(UUID enrollmentId, String status, Integer score) {
        EmployeeTraining enrollment = employeeTrainingRepository.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found"));
        enrollment.setStatus(status);
        if (score != null) enrollment.setScore(score);
        if ("COMPLETED".equals(status)) {
            enrollment.setCompletionDate(LocalDate.now());
        }
        return employeeTrainingRepository.save(enrollment);
    }

    // ==================== CERTIFICATIONS (DTO) ====================

    @Transactional
    public CertificationDTO addCertification(Certification certification) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        Employee employee = employeeRepository.findById(certification.getEmployee().getId())
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        certification.setTenant(tenant);
        certification.setEmployee(employee);
        certification.setReminderSent(false);
        certification.setStatus("ACTIVE");
        Certification saved = certificationRepository.save(certification);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<CertificationDTO> getCertifications(Pageable pageable) {
        Page<Certification> page = certificationRepository.findByTenantId(getCurrentTenantId(), pageable);
        return page.map(this::toDto);
    }

    @Transactional
    public void deleteCertification(UUID id) {
        certificationRepository.deleteById(id);
    }

    // ==================== DASHBOARD STATS ====================

    @Transactional(readOnly = true)
    public Map<String, Object> getDashboardStats() {
        UUID tenantId = getCurrentTenantId();
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTrainings", trainingProgramRepository.findByTenantId(tenantId, Pageable.unpaged()).getTotalElements());
        stats.put("activeEnrollments", employeeTrainingRepository.findByTenantId(tenantId, Pageable.unpaged()).getTotalElements());
        stats.put("certifications", certificationRepository.findByTenantId(tenantId, Pageable.unpaged()).getTotalElements());
        return stats;
    }
}