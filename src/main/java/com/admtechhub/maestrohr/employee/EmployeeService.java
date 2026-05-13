package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;
    private final DepartmentRepository departmentRepository;
    private final PayGradeRepository payGradeRepository;
    private final PaystackClient paystackClient;
    private static final String EMPLOYEE_NUMBER_PREFIX = "EMP";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Generate unique employee number: EMP-{tenantId first 4 chars}-{timestamp}
     */
    private String generateEmployeeNumber(UUID tenantId) {
        String tenantPrefix = tenantId.toString().substring(0, 4);
        String timestamp = LocalDate.now().format(DATE_FORMATTER);
        String baseNumber = String.format("%s-%s-%s", EMPLOYEE_NUMBER_PREFIX, tenantPrefix, timestamp);

        int counter = 1;
        String employeeNumber = baseNumber;
        while (employeeRepository.existsByEmployeeNumber(employeeNumber, tenantId)) {
            employeeNumber = baseNumber + "-" + counter;
            counter++;
        }
        return employeeNumber;
    }

    @Transactional
    public Employee createEmployee(Employee request) {
        // Get tenantId from TenantContext (returns String, convert to UUID)
        String tenantIdStr = TenantContext.getCurrentTenant();
        UUID tenantId = UUID.fromString(tenantIdStr);
        log.debug("Creating employee for tenant: {}", tenantId);

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found: " + tenantId));

        // Validate department exists
        Department department = departmentRepository.findById(request.getDepartment().getId())
                .orElseThrow(() -> new IllegalArgumentException("Department not found: " + request.getDepartment().getId()));

        // Validate pay grade exists
        PayGrade payGrade = payGradeRepository.findById(request.getPayGrade().getId())
                .orElseThrow(() -> new IllegalArgumentException("Pay grade not found: " + request.getPayGrade().getId()));

        // Check if email already exists for this tenant
        if (employeeRepository.existsByEmail(request.getEmail(), tenantId)) {
            throw new IllegalArgumentException("Employee with email " + request.getEmail() + " already exists");
        }

        // Generate employee number
        String employeeNumber = generateEmployeeNumber(tenantId);

        Employee employee = Employee.builder()
                .tenant(tenant)
                .employeeNumber(employeeNumber)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender())
                .maritalStatus(request.getMaritalStatus())
                .address(request.getAddress())
                .ninEncrypted(request.getNinEncrypted())
                .bvnEncrypted(request.getBvnEncrypted())
                .department(department)
                .payGrade(payGrade)
                .jobTitle(request.getJobTitle())
                .employmentType(request.getEmploymentType())
                .employmentStartDate(request.getEmploymentStartDate())
                .probationEndDate(request.getProbationEndDate())
                .bankName(request.getBankName())
                .bankAccountNumber(request.getBankAccountNumber())
                .bankAccountName(request.getBankAccountName())
                .paystackRecipientCode(request.getPaystackRecipientCode())
                .status(request.getStatus() != null ? request.getStatus() : EmployeeStatus.ACTIVE)
                .terminationDate(request.getTerminationDate())
                .build();

        Employee savedEmployee = employeeRepository.save(employee);
        log.info("Created employee with ID: {}, Number: {}", savedEmployee.getId(), savedEmployee.getEmployeeNumber());

        // After creating the employee, verify bank account and create recipient
        try {
            String bankCode = getBankCode(request.getBankName());
            var accountData = paystackClient.resolveAccount(request.getBankAccountNumber(), bankCode);

            // Verify account name matches
            if (!accountData.getAccountName().equalsIgnoreCase(request.getBankAccountName())) {
                log.warn("Account name mismatch: Expected {}, Got {}",
                        request.getBankAccountName(), accountData.getAccountName());
            }

            // Create transfer recipient
            String recipientCode = paystackClient.createTransferRecipient(
                    request.getBankAccountName(),
                    request.getBankAccountNumber(),
                    bankCode
            );

            savedEmployee.setPaystackRecipientCode(recipientCode);
            employeeRepository.save(savedEmployee);

        } catch (Exception e) {
            log.error("Failed to verify bank account for employee {}: {}",
                    savedEmployee.getEmployeeNumber(), e.getMessage());
        }

        return savedEmployee;
    }

    @Transactional(readOnly = true)
    public Page<Employee> getAllEmployees(Pageable pageable) {
        Page<Employee> employees = employeeRepository.findAll(pageable);

        // Initialize lazy-loaded relationships for each employee
        employees.getContent().forEach(employee -> {
            // Initialize department
            if (employee.getDepartment() != null) {
                employee.getDepartment().getName();
            }
            // Initialize pay grade
            if (employee.getPayGrade() != null) {
                employee.getPayGrade().getName();
                employee.getPayGrade().getBasicSalary();
            }
        });

        return employees;
    }

    @Transactional(readOnly = true)
    public Employee getEmployeeById(UUID id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));
    }

    @Transactional
    public Employee updateEmployee(UUID id, Employee request) {
        // Get tenantId for validation
        String tenantIdStr = TenantContext.getCurrentTenant();
        UUID tenantId = UUID.fromString(tenantIdStr);

        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));

        // Validate department exists
        Department department = departmentRepository.findById(request.getDepartment().getId())
                .orElseThrow(() -> new IllegalArgumentException("Department not found: " + request.getDepartment().getId()));

        // Validate pay grade exists
        PayGrade payGrade = payGradeRepository.findById(request.getPayGrade().getId())
                .orElseThrow(() -> new IllegalArgumentException("Pay grade not found: " + request.getPayGrade().getId()));

        // Check if email already exists for this tenant (excluding current employee)
        if (!employee.getEmail().equals(request.getEmail()) &&
                employeeRepository.existsByEmail(request.getEmail(), tenantId)) {
            throw new IllegalArgumentException("Employee with email " + request.getEmail() + " already exists");
        }

        // Update fields
        employee.setFirstName(request.getFirstName());
        employee.setLastName(request.getLastName());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());
        employee.setDateOfBirth(request.getDateOfBirth());
        employee.setGender(request.getGender());
        employee.setMaritalStatus(request.getMaritalStatus());
        employee.setAddress(request.getAddress());
        if (request.getNinEncrypted() != null) {
            employee.setNinEncrypted(request.getNinEncrypted());
        }
        if (request.getBvnEncrypted() != null) {
            employee.setBvnEncrypted(request.getBvnEncrypted());
        }
        employee.setDepartment(department);
        employee.setPayGrade(payGrade);
        employee.setJobTitle(request.getJobTitle());
        employee.setEmploymentType(request.getEmploymentType());
        employee.setEmploymentStartDate(request.getEmploymentStartDate());
        employee.setProbationEndDate(request.getProbationEndDate());
        employee.setBankName(request.getBankName());
        employee.setBankAccountNumber(request.getBankAccountNumber());
        employee.setBankAccountName(request.getBankAccountName());

        Employee updatedEmployee = employeeRepository.save(employee);
        log.info("Updated employee: {}", id);

        return updatedEmployee;
    }

    @Transactional
    public void terminateEmployee(UUID id, LocalDate terminationDate) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));

        employee.setStatus(EmployeeStatus.TERMINATED);
        employee.setTerminationDate(terminationDate);

        employeeRepository.save(employee);
        log.info("Terminated employee: {} on {}", id, terminationDate);
    }

    @Transactional(readOnly = true)
    public Page<Employee> searchEmployees(String searchTerm, Pageable pageable) {
        return employeeRepository.searchEmployees(searchTerm, pageable);
    }

    @Transactional(readOnly = true)
    public long countActiveEmployees() {
        return employeeRepository.countByStatus(EmployeeStatus.ACTIVE);
    }

    /**
     * Map bank name to Paystack bank code
     * This is a simplified mapping - in production, you should fetch from Paystack API
     */
    private String getBankCode(String bankName) {
        // Common Nigerian banks mapping
        Map<String, String> bankCodes = Map.ofEntries(
                Map.entry("GTBank", "058"),
                Map.entry("GTB", "058"),
                Map.entry("Guaranty Trust Bank", "058"),
                Map.entry("First Bank", "011"),
                Map.entry("FirstBank", "011"),
                Map.entry("UBA", "033"),
                Map.entry("United Bank For Africa", "033"),
                Map.entry("Access Bank", "044"),
                Map.entry("Access", "044"),
                Map.entry("Zenith Bank", "057"),
                Map.entry("Zenith", "057"),
                Map.entry("Union Bank", "032"),
                Map.entry("Union", "032"),
                Map.entry("FCMB", "214"),
                Map.entry("First City Monument Bank", "214"),
                Map.entry("Stanbic IBTC", "221"),
                Map.entry("Stanbic", "221"),
                Map.entry("Sterling Bank", "232"),
                Map.entry("Sterling", "232"),
                Map.entry("Polaris Bank", "076"),
                Map.entry("Polaris", "076"),
                Map.entry("Ecobank", "050"),
                Map.entry("Eco", "050")
        );

        // Try exact match
        String code = bankCodes.get(bankName);
        if (code != null) return code;

        // Try case-insensitive match
        for (Map.Entry<String, String> entry : bankCodes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(bankName)) {
                return entry.getValue();
            }
        }

        // Default fallback
        throw new IllegalArgumentException("Bank not supported: " + bankName + ". Please use a supported bank.");
    }

    @Transactional(readOnly = true)
    public Employee getEmployeeByIdWithDetails(UUID id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));

        // Force initialization of lazy-loaded relationships
        if (employee.getDepartment() != null) {
            employee.getDepartment().getName();
            employee.getDepartment().getId();
        }
        if (employee.getPayGrade() != null) {
            employee.getPayGrade().getName();
            employee.getPayGrade().getBasicSalary();
        }
        if (employee.getTenant() != null) {
            employee.getTenant().getCompanyName();
        }

        return employee;
    }

}