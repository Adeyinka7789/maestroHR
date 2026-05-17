package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/employees")
@RequiredArgsConstructor
public class EmployeeWebController {

    private final EmployeeService employeeService;
    private final DepartmentRepository departmentRepository;
    private final PayGradeRepository payGradeRepository;
    private final JwtService jwtService;

    @GetMapping
    public String listEmployees(HttpServletRequest request) {
        if (!setTenant(request)) return "redirect:/login";
        TenantContext.clear();
        return "redirect:/employees.html";
    }

    @GetMapping("/create")
    public String showCreateForm(HttpServletRequest request) {
        if (!setTenant(request)) return "redirect:/login";
        TenantContext.clear();
        return "redirect:/employee-create.html";
    }

    @GetMapping("/{id}")
    public String viewEmployee(@PathVariable UUID id, HttpServletRequest request) {
        if (!setTenant(request)) return "redirect:/login";
        TenantContext.clear();
        return "redirect:/employee-view.html?id=" + id;
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable UUID id, HttpServletRequest request) {
        if (!setTenant(request)) return "redirect:/login";
        TenantContext.clear();
        return "redirect:/employee-edit.html?id=" + id;
    }

    private boolean setTenant(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) return false;
        try {
            if (!jwtService.isTokenValid(token)) return false;
            TenantContext.setCurrentTenant(jwtService.extractTenantId(token));
            return true;
        } catch (Exception e) {
            log.error("JWT validation error: {}", e.getMessage());
            return false;
        }
    }

    private String extractToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7);
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("maestrohr_token".equals(c.getName())) return c.getValue();
            }
        }
        return null;
    }
}
