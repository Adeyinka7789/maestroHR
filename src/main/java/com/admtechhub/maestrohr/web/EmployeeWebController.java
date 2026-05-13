package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.*;
import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Controller
@RequestMapping("/employees")
@RequiredArgsConstructor
public class EmployeeWebController {

    private final EmployeeService employeeService;
    private final DepartmentRepository departmentRepository;
    private final PayGradeRepository payGradeRepository;

    @GetMapping
    public String listEmployees(Model model,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "10") int size) {
        try {
            Page<Employee> employees = employeeService.getAllEmployees(PageRequest.of(page, size));

            model.addAttribute("pageTitle", "Employees");
            model.addAttribute("employees", employees);
            model.addAttribute("currentPage", page);
            model.addAttribute("totalPages", employees.getTotalPages());
            model.addAttribute("totalEmployees", employees.getTotalElements());  // Add this line

        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("error", e.getMessage());
            model.addAttribute("employees", Page.empty());
        }

        return "employees/list";
    }

    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("pageTitle", "Create Employee");
        model.addAttribute("employee", new Employee());
        model.addAttribute("departments", departmentRepository.findAll());
        model.addAttribute("payGrades", payGradeRepository.findAll());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("maritalStatuses", MaritalStatus.values());
        model.addAttribute("employmentTypes", EmploymentType.values());

        return "employees/create";
    }

    @GetMapping("/{id}")
    public String viewEmployee(@PathVariable UUID id, Model model) {
        try {
            Employee employee = employeeService.getEmployeeByIdWithDetails(id);
            model.addAttribute("pageTitle", "Employee Details");
            model.addAttribute("employee", employee);
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
        }

        return "employees/view";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable UUID id, Model model) {
        try {
            Employee employee = employeeService.getEmployeeById(id);
            model.addAttribute("pageTitle", "Edit Employee");
            model.addAttribute("employee", employee);
            model.addAttribute("departments", departmentRepository.findAll());
            model.addAttribute("payGrades", payGradeRepository.findAll());
            model.addAttribute("genders", Gender.values());
            model.addAttribute("maritalStatuses", MaritalStatus.values());
            model.addAttribute("employmentTypes", EmploymentType.values());
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
        }

        return "employees/edit";
    }
}