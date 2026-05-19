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

    private UUID getCurrentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }

    // Training Programs
    @Transactional
    public TrainingProgram createTrainingProgram(TrainingProgram program) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        program.setTenant(tenant);
        program.setStatus("ACTIVE");
        return trainingProgramRepository.save(program);
    }

    @Transactional(readOnly = true)
    public Page<TrainingProgram> getTrainingPrograms(Pageable pageable) {
        return trainingProgramRepository.findByTenantId(getCurrentTenantId(), pageable);
    }

    @Transactional
    public void deleteTrainingProgram(UUID id) {
        trainingProgramRepository.deleteById(id);
    }

    // Employee Enrollments
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

    // Certifications
    @Transactional
    public Certification addCertification(Certification certification) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        Employee employee = employeeRepository.findById(certification.getEmployee().getId())
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        certification.setTenant(tenant);
        certification.setEmployee(employee);
        certification.setReminderSent(false);
        certification.setStatus("ACTIVE");
        return certificationRepository.save(certification);
    }

    @Transactional(readOnly = true)
    public Page<Certification> getCertifications(Pageable pageable) {
        return certificationRepository.findByTenantId(getCurrentTenantId(), pageable);
    }

    @Transactional
    public Map<String, Object> getDashboardStats() {
        UUID tenantId = getCurrentTenantId();
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTrainings", trainingProgramRepository.findByTenantId(tenantId, Pageable.unpaged()).getTotalElements());
        stats.put("activeEnrollments", employeeTrainingRepository.findByTenantId(tenantId, Pageable.unpaged()).getTotalElements());
        stats.put("certifications", certificationRepository.findByTenantId(tenantId, Pageable.unpaged()).getTotalElements());
        return stats;
    }

    // ==================== CERTIFICATION METHODS ====================

    @Transactional
    public void deleteCertification(UUID id) {
        certificationRepository.deleteById(id);
    }
}