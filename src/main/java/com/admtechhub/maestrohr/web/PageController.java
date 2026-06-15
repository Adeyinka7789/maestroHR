package com.admtechhub.maestrohr.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@Controller
public class PageController {

    // Core pages
    // NOTE: /htmx/dashboard is owned by DashboardController (server-rendered fragment pilot).
    @GetMapping("/htmx/employees")
    public String employees(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/employees.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/departments")
    public String departments(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/departments.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/pay-grades")
    public String payGrades(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/pay-grades.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/payroll")
    public String payroll(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/payroll.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/reports")
    public String reports(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/reports.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/leave")
    public String leave(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/leave.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/attendance")
    public String attendance(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/attendance.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/recruitment")
    public String recruitment(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/recruitment.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/performance-reviews")
    public String performanceReviews(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/performance-reviews.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/training-management")
    public String trainingManagement(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/training-management.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/exit-management")
    public String exitManagement(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/exit-management.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/audit")
    public String audit(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/audit.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/settings")
    public String settings(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/settings.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/admin")
    public String admin(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/admin.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/admin/pricing")
    public String adminPricing(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/plans.html" : "forward:/layout.html";
    }

    // Employee CRUD
    @GetMapping("/htmx/employee-create")
    public String employeeCreate(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/employee-create.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/employee-edit")
    public String employeeEdit(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/employee-edit.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/employee-view")
    public String employeeView(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/employee-view.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/employee-dashboard")
    public String employeeDashboard(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/employee-dashboard.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/payroll-detail")
    public String payrollDetail(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/payroll-detail.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/plans")
    public String plans(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/plans.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/checkout")
    public String htmxCheckout(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/checkout.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/subscribers")
    public String subscribers(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/subscribers.html" : "forward:/layout.html";
    }

}