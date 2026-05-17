package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.tenant.TenantRepository;
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
    private final TenantRepository tenantRepository;  // Add this dependency

    @Transactional
    public Department create(String name) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found: " + tenantId));

        if (departmentRepository.existsByNameAndTenantId(name, tenantId)) {
            throw new IllegalArgumentException(
                    "Department '" + name + "' already exists"
            );
        }

        Department department = Department.builder()
                .tenant(tenant)  // Changed from .tenantId(tenantId) to .tenant(tenant)
                .name(name)
                .build();

        return departmentRepository.save(department);
    }

    @Transactional(readOnly = true)
    public List<Department> findAll() {
        try {
            String tenantIdStr = TenantContext.getCurrentTenant();
            System.out.println("Tenant ID from context: " + tenantIdStr);

            UUID tenantId = UUID.fromString(tenantIdStr);
            System.out.println("Looking for departments with tenantId: " + tenantId);

            List<Department> departments = departmentRepository.findAllByTenantId(tenantId);
            System.out.println("Found " + departments.size() + " departments");

            // Initialize the tenant for each department to avoid lazy loading issues
            departments.forEach(dept -> {
                if (dept.getTenant() != null) {
                    // Force initialization of the tenant proxy
                    dept.getTenant().getId();
                    dept.getTenant().getCompanyName();
                }
            });

            return departments;
        } catch (Exception e) {
            System.out.println("Error in findAll: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
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