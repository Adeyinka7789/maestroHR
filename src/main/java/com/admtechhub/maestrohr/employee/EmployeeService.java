package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
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

        // Reuse an existing login when this work email already has a User account in THIS
        // tenant (e.g. an HR_ADMIN being given an Employee profile); only provision a new
        // EMPLOYEE login when none exists. Scoped to the tenant so a same-email user in
        // another tenant is never linked across the boundary — that tenant gets its own User.
        User savedUser = userRepository.findByEmailAndTenantId(request.getEmail(), tenantId)
                .map(existing -> {
                    log.info("Linking employee to existing user account: {}", existing.getEmail());
                    return existing;
                })
                .orElseGet(() -> {
                    User user = User.builder()
                            .tenantId(tenantId)
                            .email(request.getEmail())
                            .passwordHash(passwordEncoder.encode(request.getPassword()))
                            .role(UserRole.EMPLOYEE)
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
                .status(EmployeeStatus.ACTIVE)
                .build();

        Employee savedEmployee = employeeRepository.save(employee);
        log.info("Created employee with ID: {}, Number: {}", savedEmployee.getId(), savedEmployee.getEmployeeNumber());

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

        employee.setStatus(EmployeeStatus.TERMINATED);
        employee.setTerminationDate(terminationDate);

        if (employee.getUser() != null) {
            employee.getUser().setActive(false);
            userRepository.save(employee.getUser());
        }

        employeeRepository.save(employee);
        log.info("Terminated employee: {} on {}", id, terminationDate);
    }

    /**
     * Whether {@code id} can be permanently removed: true only when the employee has no
     * payroll history, no leave requests, no attendance records, and does not currently
     * head a department. Drives the SUPER_ADMIN-only "Delete permanently" button so it is
     * never offered for an employee whose deletion would orphan records — for those, the
     * non-destructive {@link #terminateEmployee} path is the only option. Read-only; the
     * authoritative re-check lives in {@link #hardDeleteEmployee}.
     */
    @Transactional(readOnly = true)
    public boolean canHardDelete(UUID id) {
        return !payrollEntryRepository.existsByEmployeeId(id)
                && !leaveRequestRepository.existsByEmployeeId(id)
                && !attendanceRepository.existsByEmployeeId(id)
                && !departmentRepository.existsByHeadEmployeeId(id.toString());
    }

    /**
     * Permanently delete an employee and its linked login. Allowed only when the employee
     * has no dependent records (see {@link #canHardDelete}); otherwise blocks with an
     * {@link IllegalStateException} so the caller can surface why and steer the user to
     * Terminate instead. The check is re-run here (not just in the UI) so the invariant
     * holds even against a stale button or a direct request. The employee owns the FK to
     * its {@link User}, so we delete the employee first, then the now-unreferenced login.
     */
    @Transactional
    public void hardDeleteEmployee(UUID id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));

        if (payrollEntryRepository.existsByEmployeeId(id)) {
            throw new IllegalStateException(
                    "This employee has payroll history and cannot be permanently deleted. Terminate them instead.");
        }
        if (leaveRequestRepository.existsByEmployeeId(id)) {
            throw new IllegalStateException(
                    "This employee has leave records and cannot be permanently deleted. Terminate them instead.");
        }
        if (attendanceRepository.existsByEmployeeId(id)) {
            throw new IllegalStateException(
                    "This employee has attendance records and cannot be permanently deleted. Terminate them instead.");
        }
        if (departmentRepository.existsByHeadEmployeeId(id.toString())) {
            throw new IllegalStateException(
                    "This employee currently heads a department. Reassign the department head before deleting.");
        }

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

        // Initialize lazy-loaded proxies before accessing their properties
        allEmployees.forEach(emp -> {
            if (emp.getDepartment() != null) {
                emp.getDepartment().getName(); // force initialization
            }
            if (emp.getPayGrade() != null) {
                emp.getPayGrade().getName(); // force initialization
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
            String[] columns = {"Employee Number", "First Name", "Last Name", "Email", "Phone",
                    "Department", "Job Title", "Employment Type", "Status", "Bank",
                    "Account Number", "Account Name"};
            for (int i = 0; i < columns.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (Employee emp : allEmployees) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(emp.getEmployeeNumber());
                row.createCell(1).setCellValue(emp.getFirstName());
                row.createCell(2).setCellValue(emp.getLastName());
                row.createCell(3).setCellValue(emp.getEmail());
                row.createCell(4).setCellValue(emp.getPhone());
                row.createCell(5).setCellValue(emp.getDepartment() != null ? emp.getDepartment().getName() : "");
                row.createCell(6).setCellValue(emp.getJobTitle());
                row.createCell(7).setCellValue(emp.getEmploymentType() != null ? emp.getEmploymentType().name() : "");
                row.createCell(8).setCellValue(emp.getStatus().name());
                row.createCell(9).setCellValue(emp.getBankName());
                row.createCell(10).setCellValue(emp.getBankAccountNumber());
                row.createCell(11).setCellValue(emp.getBankAccountName());
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

    @Transactional
    public Map<String, Object> importEmployeesFromCSV(MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        int successCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found"));

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean isFirstLine = true;

            while ((line = reader.readLine()) != null) {
                if (isFirstLine) { isFirstLine = false; continue; }

                String[] data = line.split(",");
                if (data.length < 10) {
                    errorCount++;
                    errors.add("Invalid row: " + line);
                    continue;
                }

                try {
                    EmployeeRequest request = new EmployeeRequest();
                    request.setFirstName(data[0].trim());
                    request.setLastName(data[1].trim());
                    request.setEmail(data[2].trim());
                    request.setPhone(data[3].trim());
                    request.setJobTitle(data[4].trim());
                    request.setEmploymentType(EmploymentType.valueOf(data[5].trim()));
                    request.setBankName(data[6].trim());
                    request.setBankAccountNumber(data[7].trim());
                    request.setBankAccountName(data[8].trim());
                    request.setPassword("Welcome123!");

                    String deptName = data.length > 9 ? data[9].trim() : "General";
                    Department dept = departmentRepository.findAllByTenantId(tenantId)
                            .stream().filter(d -> d.getName().equalsIgnoreCase(deptName))
                            .findFirst()
                            .orElseGet(() -> {
                                Department newDept = Department.builder()
                                        .tenant(tenant)
                                        .name(deptName)
                                        .build();
                                return departmentRepository.save(newDept);
                            });
                    request.setDepartmentId(dept.getId());

                    PayGrade defaultPayGrade = payGradeRepository.findAllByTenantId(tenantId).stream().findFirst()
                            .orElseThrow(() -> new RuntimeException("No pay grade found"));
                    request.setPayGradeId(defaultPayGrade.getId());

                    request.setEmploymentStartDate(LocalDate.now());
                    request.setDateOfBirth(LocalDate.of(1990, 1, 1));
                    request.setGender(Gender.MALE);
                    request.setMaritalStatus(MaritalStatus.SINGLE);
                    request.setAddress("Imported address");

                    createEmployee(request);
                    successCount++;
                } catch (Exception e) {
                    errorCount++;
                    errors.add("Row error: " + line + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse CSV file", e);
        }

        result.put("successCount", successCount);
        result.put("errorCount", errorCount);
        result.put("errors", errors);
        return result;
    }

    private EmployeeDetailsDTO toDetailsDto(Employee employee) {
        return new EmployeeDetailsDTO(employee);
    }

    private EmployeeSummaryDTO toSummaryDto(Employee employee) {
        return new EmployeeSummaryDTO(employee);
    }
}