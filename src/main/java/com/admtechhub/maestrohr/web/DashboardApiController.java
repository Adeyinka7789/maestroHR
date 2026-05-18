package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardApiController {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollRunRepository payrollRunRepository;

    /**
     * GET /api/dashboard/stats
     * Returns dashboard statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        try {
            UUID tenantId = getCurrentTenantId();

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalEmployees", employeeRepository.count());
            stats.put("activeEmployees", employeeRepository.countByStatus(EmployeeStatus.ACTIVE));
            stats.put("pendingLeaveRequests", (long) leaveRequestRepository.findByStatus(LeaveStatus.PENDING).size());

            log.debug("Dashboard stats retrieved for tenant: {}", tenantId);
            return ResponseEntity.ok(ApiResponse.success("Stats retrieved", stats));
        } catch (Exception e) {
            log.error("Error getting dashboard stats: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success("Stats retrieved", Map.of()));
        }
    }

    /**
     * GET /api/dashboard/payroll-trend
     * Returns last 6 months payroll totals
     */
    @GetMapping("/payroll-trend")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getPayrollTrend() {
        try {
            UUID tenantId = getCurrentTenantId();
            YearMonth now = YearMonth.now();
            Map<String, Long> monthlyTotals = new LinkedHashMap<>();

            for (int i = 5; i >= 0; i--) {
                YearMonth ym = now.minusMonths(i);
                var payroll = payrollRunRepository.findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(
                        tenantId, ym.getMonthValue(), ym.getYear()
                ).orElse(null);
                monthlyTotals.put(ym.toString(), payroll != null ? payroll.getTotalNet() / 100 : 0L);
            }

            log.debug("Payroll trend retrieved for tenant: {}", tenantId);
            return ResponseEntity.ok(ApiResponse.success("Payroll trend retrieved", monthlyTotals));
        } catch (Exception e) {
            log.error("Error getting payroll trend: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success("Payroll trend retrieved", Map.of()));
        }
    }

    /**
     * GET /api/dashboard/department-headcounts
     * Returns employee count per department
     */
    @GetMapping("/department-headcounts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getDepartmentHeadcounts() {
        try {
            UUID tenantId = getCurrentTenantId();
            Map<String, Long> headcounts = new LinkedHashMap<>();

            departmentRepository.findAll().forEach(department -> {
                long count = employeeRepository.findByDepartmentId(department.getId()).size();
                if (count > 0) {
                    headcounts.put(department.getName(), count);
                }
            });

            log.debug("Department headcounts retrieved for tenant: {}", tenantId);
            return ResponseEntity.ok(ApiResponse.success("Department headcounts retrieved", headcounts));
        } catch (Exception e) {
            log.error("Error getting department headcounts: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success("Department headcounts retrieved", Map.of()));
        }
    }

    @GetMapping("/upcoming-birthdays")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUpcomingBirthdays() {
        try {
            UUID tenantId = getCurrentTenantId();
            List<Employee> employees = employeeRepository.findAllByTenantId(tenantId, Pageable.unpaged()).getContent();

            LocalDate today = LocalDate.now();

            List<Map<String, Object>> birthdays = employees.stream()
                    .filter(e -> e.getDateOfBirth() != null)
                    .map(e -> {
                        LocalDate dob = e.getDateOfBirth();
                        LocalDate thisYearBirthday = dob.withYear(today.getYear());
                        if (thisYearBirthday.isBefore(today)) {
                            thisYearBirthday = thisYearBirthday.plusYears(1);
                        }
                        long daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, thisYearBirthday);

                        Map<String, Object> birthday = new HashMap<>();
                        birthday.put("employeeId", e.getId());
                        birthday.put("employeeName", e.getFullName());
                        birthday.put("birthdayDate", thisYearBirthday.toString());
                        birthday.put("daysUntil", (int) daysUntil);
                        String avatar = String.valueOf((e.getFirstName() != null && e.getFirstName().length() > 0 ? e.getFirstName().charAt(0) : '?'));
                        birthday.put("avatar", avatar);
                        return birthday;
                    })
                    .filter(b -> (int)b.get("daysUntil") <= 7)
                    .sorted((a, b) -> Integer.compare((int)a.get("daysUntil"), (int)b.get("daysUntil")))
                    .limit(5)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.success("Upcoming birthdays retrieved", birthdays));
        } catch (Exception e) {
            log.error("Error getting birthdays", e);
            return ResponseEntity.ok(ApiResponse.success("Birthdays retrieved", new ArrayList<>()));
        }
    }

    private UUID getCurrentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}