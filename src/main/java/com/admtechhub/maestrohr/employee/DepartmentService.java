package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;

    @Transactional
    public Department create(String name) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());

        if (departmentRepository.existsByNameAndTenantId(name, tenantId)) {
            throw new IllegalArgumentException(
                    "Department '" + name + "' already exists"
            );
        }

        Department department = Department.builder()
                .tenantId(tenantId)
                .name(name)
                .build();

        return departmentRepository.save(department);
    }

    @Transactional(readOnly = true)
    public List<Department> findAll() {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        return departmentRepository.findAllByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Department findById(UUID id) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Department not found"
                ));
    }

    @Transactional
    public Department update(UUID id, String name) {
        Department department = findById(id);
        department.setName(name);
        return departmentRepository.save(department);
    }

    @Transactional
    public void delete(UUID id) {
        Department department = findById(id);
        departmentRepository.delete(department);
    }
}