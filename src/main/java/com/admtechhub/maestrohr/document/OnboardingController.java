package com.admtechhub.maestrohr.document;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
import com.admtechhub.maestrohr.employee.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-service onboarding checklist API, consumed by the employee dashboard. Tasks are seeded
 * when an employee is created with status=ONBOARDING (see {@code EmployeeService}); the employee
 * reads and ticks off their own list here. The employee is always resolved from the session, so
 * a user can only ever see/complete their own tasks (the IDOR guard).
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final EmployeeService employeeService;

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'DEPT_MANAGER', 'HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ApiResponse<List<OnboardingTaskDTO>> myTasks() {
        EmployeeDetailsDTO me = currentEmployee();
        List<OnboardingTaskDTO> tasks = onboardingService.getTasksByEmployee(me.getId()).stream()
                .map(OnboardingTaskDTO::from)
                .toList();
        return ApiResponse.success("Onboarding tasks", tasks);
    }

    @PostMapping("/tasks/{id}/complete")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'DEPT_MANAGER', 'HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ApiResponse<Void> complete(@PathVariable UUID id) {
        EmployeeDetailsDTO me = currentEmployee();
        // IDOR guard: the task must belong to the authenticated employee.
        boolean owned = onboardingService.getTasksByEmployee(me.getId()).stream()
                .anyMatch(t -> t.getId().equals(id));
        if (!owned) {
            throw new AccessDeniedException("You can only complete your own onboarding tasks.");
        }
        onboardingService.completeTask(id);
        return ApiResponse.success("Task completed", null);
    }

    private EmployeeDetailsDTO currentEmployee() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return employeeService.findByEmail(email);
    }
}
