package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.document.OnboardingService;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final PayrollEntryRepository payrollEntryRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AttendanceRepository attendanceRepository;
    private final OnboardingService onboardingService;

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

    private UUID getCurrentTenantId() {
        String tenantIdStr = TenantContext.getCurrentTenant();
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantIdStr);
    }

    @Transactional
    public EmployeeDetailsDTO createEmployee(EmployeeRequest request) {
        UUID tenantId = getCurrentTenantId();
        log.debug("Creating employee for tenant: {}", tenantId);

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found: " + tenantId));

        Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new IllegalArgumentException("Department not found: " + request.getDepartmentId()));

        PayGrade payGrade = payGradeRepository.findById(request.getPayGradeId())
                .orElseThrow(() -> new IllegalArgumentException("Pay grade not found: " + request.getPayGradeId()));

        if (employeeRepository.existsByEmail(request.getEmail(), tenantId)) {
            throw new IllegalArgumentException("Employee with email " + request.getEmail() + " already exists");
        }

        String employeeNumber = generateEmployeeNumber(tenantId);

        final UserRole assignedRole;
        if (request.getRole() != null && !request.getRole().isBlank()) {
            try {
                assignedRole = UserRole.valueOf(request.getRole().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid role: " + request.getRole());
            }
        } else {
            assignedRole = UserRole.EMPLOYEE;
        }

        if ((assignedRole == UserRole.HR_ADMIN || assignedRole == UserRole.SUPER_ADMIN)
                && !currentUserIsSuperAdmin()) {
            throw new IllegalArgumentException("Only a super-admin can assign HR_ADMIN or SUPER_ADMIN roles.");
        }

        User savedUser = userRepository.findByEmailAndTenantId(request.getEmail(), tenantId)
                .map(existing -> {
                    log.info("Linking employee to existing user account: {}", existing.getEmail());
                    if (existing.getRole() != assignedRole) {
                        existing.setRole(assignedRole);
                        userRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    User user = User.builder()
                            .tenantId(tenantId)
                            .email(request.getEmail())
                            .passwordHash(passwordEncoder.encode(request.getPassword()))
                            .role(assignedRole)
                            .isActive(true)
                            .failedLoginAttempts(0)
                            .build();
                    User created = userRepository.save(user);
                    log.info("Created user account for employee: {}", created.getEmail());
                    return created;
                });

        Employee employee = Employee.builder()
                .tenant(tenant)
                .user(savedUser)
                .employeeNumber(employeeNumber)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender())
                .maritalStatus(request.getMaritalStatus())
                .address(request.getAddress())
                .ninEncrypted(request.getNin())
                .bvnEncrypted(request.getBvn())
                .department(department)
                .payGrade(payGrade)
                .jobTitle(request.getJobTitle())
                .employmentType(request.getEmploymentType())
                .employmentStartDate(request.getEmploymentStartDate())
                .probationEndDate(request.getProbationEndDate())
                .bankName(request.getBankName())
                .bankAccountNumber(request.getBankAccountNumber())
                .bankAccountName(request.getBankAccountName())
                .status(request.getStatus() != null ? request.getStatus() : EmployeeStatus.ACTIVE)
                .build();

        Employee savedEmployee = employeeRepository.save(employee);
        log.info("Created employee with ID: {}, Number: {}", savedEmployee.getId(), savedEmployee.getEmployeeNumber());

        // New hires created in the ONBOARDING state get the default onboarding checklist seeded.
        if (savedEmployee.getStatus() == EmployeeStatus.ONBOARDING) {
            onboardingService.createDefaultTasksForEmployee(savedEmployee.getId());
        }

        try {
            String bankCode = getBankCode(request.getBankName());
            var accountData = paystackClient.resolveAccount(request.getBankAccountNumber(), bankCode);

            if (!accountData.getAccountName().equalsIgnoreCase(request.getBankAccountName())) {
                log.warn("Account name mismatch: Expected {}, Got {}",
                        request.getBankAccountName(), accountData.getAccountName());
            }

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

        try {
            notificationService.sendWelcomeNotification(savedEmployee, request.getPassword());
        } catch (Exception e) {
            log.error("Failed to send welcome notification: {}", e.getMessage());
        }

        return toDetailsDto(savedEmployee);
    }

    @Transactional(readOnly = true)
    public Page<Employee> getAllEmployees(Pageable pageable) {
        UUID tenantId = getCurrentTenantId();
        Page<Employee> employees = employeeRepository.findAllByTenantId(tenantId, pageable);

        employees.getContent().forEach(employee -> {
            if (employee.getDepartment() != null) {
                employee.getDepartment().getName();
                employee.getDepartment().getId();
            }
            if (employee.getPayGrade() != null) {
                employee.getPayGrade().getName();
                employee.getPayGrade().getBasicSalary();
                employee.getPayGrade().getId();
            }
        });

        return employees;
    }

    @Transactional(readOnly = true)
    public EmployeeDetailsDTO getEmployeeById(UUID id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));
        return toDetailsDto(employee);
    }

    @Transactional
    public EmployeeDetailsDTO updateEmployee(UUID id, UpdateEmployeeRequest request) {
        UUID tenantId = getCurrentTenantId();
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));

        Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new IllegalArgumentException("Department not found: " + request.getDepartmentId()));

        PayGrade payGrade = payGradeRepository.findById(request.getPayGradeId())
                .orElseThrow(() -> new IllegalArgumentException("Pay grade not found: " + request.getPayGradeId()));

        if (!employee.getEmail().equals(request.getEmail()) &&
                employeeRepository.existsByEmail(request.getEmail(), tenantId)) {
            throw new IllegalArgumentException("Employee with email " + request.getEmail() + " already exists");
        }

        if (employee.getUser() != null && !employee.getEmail().equals(request.getEmail())) {
            User user = employee.getUser();
            user.setEmail(request.getEmail());
            userRepository.save(user);
        }

        if (request.getDeviceEnrollmentId() != null) {
            employee.setDeviceEnrollmentId(request.getDeviceEnrollmentId().trim());
        }

        if (request.getRole() != null && !request.getRole().isBlank()) {
            UserRole newRole;
            try {
                newRole = UserRole.valueOf(request.getRole().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid role: " + request.getRole());
            }
            if ((newRole == UserRole.HR_ADMIN || newRole == UserRole.SUPER_ADMIN)
                    && !currentUserIsSuperAdmin()) {
                throw new IllegalArgumentException("Only a super-admin can assign HR_ADMIN or SUPER_ADMIN roles.");
            }
            if (employee.getUser() != null) {
                employee.getUser().setRole(newRole);
                userRepository.save(employee.getUser());
            }
        }

        employee.setFirstName(request.getFirstName());
        employee.setLastName(request.getLastName());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());
        employee.setDateOfBirth(request.getDateOfBirth());
        employee.setGender(request.getGender());
        employee.setMaritalStatus(request.getMaritalStatus());
        employee.setAddress(request.getAddress());
        if (request.getNin() != null) {
            employee.setNinEncrypted(request.getNin());
        }
        if (request.getBvn() != null) {
            employee.setBvnEncrypted(request.getBvn());
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

        return toDetailsDto(updatedEmployee);
    }

    @Transactional
    public void terminateEmployee(UUID id, LocalDate terminationDate) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));

        if (employee.getStatus() == EmployeeStatus.TERMINATED) {
            throw new IllegalStateException("Employee is already terminated.");
        }

        employee.setStatus(EmployeeStatus.TERMINATED);
        employee.setTerminationDate(terminationDate);

        if (employee.getUser() != null) {
            employee.getUser().setActive(false);
            userRepository.save(employee.getUser());
        }

        employeeRepository.save(employee);
        log.info("Terminated employee: {} on {}", id, terminationDate);
    }

    @Transactional(readOnly = true)
    public boolean canHardDelete(UUID id) {
        return !payrollEntryRepository.existsByEmployeeId(id)
                && !leaveRequestRepository.existsByEmployeeId(id)
                && !attendanceRepository.existsByEmployeeId(id)
                && !departmentRepository.existsByHeadEmployeeId(id.toString());
    }

    private void assertNoDependents(UUID id) {
        if (payrollEntryRepository.existsByEmployeeId(id)) {
            throw new IllegalStateException("This employee has payroll history and cannot be deleted. Terminate them instead.");
        }
        if (leaveRequestRepository.existsByEmployeeId(id)) {
            throw new IllegalStateException("This employee has leave records and cannot be deleted. Terminate them instead.");
        }
        if (attendanceRepository.existsByEmployeeId(id)) {
            throw new IllegalStateException("This employee has attendance records and cannot be deleted. Terminate them instead.");
        }
        if (departmentRepository.existsByHeadEmployeeId(id.toString())) {
            throw new IllegalStateException("This employee currently heads a department. Reassign the department head before deleting.");
        }
    }

    @Transactional
    public void softDeleteEmployee(UUID id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));
        assertNoDependents(id);

        employee.setDeletedAt(OffsetDateTime.now());
        if (employee.getUser() != null) {
            employee.getUser().setActive(false);
            userRepository.save(employee.getUser());
        }
        employeeRepository.save(employee);
        log.info("Soft-deleted employee: {}", id);
    }

    @Transactional
    public void hardDeleteEmployee(UUID id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));
        assertNoDependents(id);

        User linkedUser = employee.getUser();
        employeeRepository.delete(employee);
        if (linkedUser != null) {
            userRepository.delete(linkedUser);
        }
        log.info("Permanently deleted employee: {}", id);
    }

    @Transactional(readOnly = true)
    public Page<Employee> searchEmployees(String searchTerm, Pageable pageable) {
        return employeeRepository.searchEmployees(searchTerm, pageable);
    }

    @Transactional(readOnly = true)
    public long countActiveEmployees() {
        UUID tenantId = getCurrentTenantId();
        return employeeRepository.countByTenantIdAndStatus(tenantId, EmployeeStatus.ACTIVE);
    }

    private String getBankCode(String bankName) {
        Map<String, String> bankCodes = Map.ofEntries(
                Map.entry("GTBank", "058"), Map.entry("GTB", "058"), Map.entry("Guaranty Trust Bank", "058"),
                Map.entry("First Bank", "011"), Map.entry("FirstBank", "011"),
                Map.entry("UBA", "033"), Map.entry("United Bank For Africa", "033"),
                Map.entry("Access Bank", "044"), Map.entry("Access", "044"),
                Map.entry("Zenith Bank", "057"), Map.entry("Zenith", "057"),
                Map.entry("Union Bank", "032"), Map.entry("Union", "032"),
                Map.entry("FCMB", "214"), Map.entry("First City Monument Bank", "214"),
                Map.entry("Stanbic IBTC", "221"), Map.entry("Stanbic", "221"),
                Map.entry("Sterling Bank", "232"), Map.entry("Sterling", "232"),
                Map.entry("Polaris Bank", "076"), Map.entry("Polaris", "076"),
                Map.entry("Ecobank", "050"), Map.entry("Eco", "050")
        );

        String code = bankCodes.get(bankName);
        if (code != null) return code;

        for (Map.Entry<String, String> entry : bankCodes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(bankName)) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException("Bank not supported: " + bankName);
    }

    @Transactional(readOnly = true)
    public Employee getEmployeeByIdWithDetails(UUID id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));

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

    @Transactional(readOnly = true)
    public EmployeeDetailsDTO findByEmail(String email) {
        Employee employee = employeeRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found with email: " + email));
        return toDetailsDto(employee);
    }

    @Transactional(readOnly = true)
    public byte[] exportEmployeesToExcel() {
        UUID tenantId = getCurrentTenantId();
        List<Employee> allEmployees = new ArrayList<>();
        int page = 0;
        int size = 500;
        Page<Employee> pageResult;
        do {
            pageResult = employeeRepository.findAllByTenantId(tenantId, PageRequest.of(page, size));
            allEmployees.addAll(pageResult.getContent());
            page++;
        } while (pageResult.hasNext());

        allEmployees.forEach(emp -> {
            if (emp.getDepartment() != null) {
                emp.getDepartment().getName();
            }
            if (emp.getPayGrade() != null) {
                emp.getPayGrade().getName();
            }
        });

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Employees");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(0);

            // Headers mirror EmployeeImportService.TEMPLATE_HEADERS so an exported file can be
            // re-imported as-is (the importer maps by header name, not position).
            String[] columns = {
                    "First Name",       // 0
                    "Last Name",        // 1
                    "Email",            // 2
                    "Phone",            // 3
                    "Job Title",        // 4
                    "Employment Type",  // 5
                    "Bank Name",        // 6
                    "Account Number",   // 7
                    "Account Name",     // 8
                    "Department",       // 9
                    "Pay Grade",        // 10
                    "Date of Birth",    // 11
                    "Gender",           // 12
                    "Marital Status",   // 13
                    "Address",          // 14
                    "Start Date"        // 15
            };

            for (int i = 0; i < columns.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (Employee emp : allEmployees) {
                Row row = sheet.createRow(rowNum++);

                // Values written at the same indices as the header names above.
                row.createCell(0).setCellValue(emp.getFirstName());
                row.createCell(1).setCellValue(emp.getLastName());
                row.createCell(2).setCellValue(emp.getEmail());
                row.createCell(3).setCellValue(emp.getPhone());
                row.createCell(4).setCellValue(emp.getJobTitle());
                row.createCell(5).setCellValue(emp.getEmploymentType() != null ? emp.getEmploymentType().name() : "");
                row.createCell(6).setCellValue(emp.getBankName());
                row.createCell(7).setCellValue(emp.getBankAccountNumber());
                row.createCell(8).setCellValue(emp.getBankAccountName());
                row.createCell(9).setCellValue(emp.getDepartment() != null ? emp.getDepartment().getName() : "");
                row.createCell(10).setCellValue(emp.getPayGrade() != null ? emp.getPayGrade().getName() : "");
                row.createCell(11).setCellValue(emp.getDateOfBirth() != null ? emp.getDateOfBirth().toString() : "");
                row.createCell(12).setCellValue(emp.getGender() != null ? emp.getGender().name() : "");
                row.createCell(13).setCellValue(emp.getMaritalStatus() != null ? emp.getMaritalStatus().name() : "");
                row.createCell(14).setCellValue(emp.getAddress() != null ? emp.getAddress() : "");
                row.createCell(15).setCellValue(emp.getEmploymentStartDate() != null ? emp.getEmploymentStartDate().toString() : "");
            }

            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Excel export failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate Excel export", e);
        }
    }

    private EmployeeDetailsDTO toDetailsDto(Employee employee) {
        return new EmployeeDetailsDTO(employee);
    }

    private EmployeeSummaryDTO toSummaryDto(Employee employee) {
        return new EmployeeSummaryDTO(employee);
    }

    private boolean currentUserIsSuperAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
}