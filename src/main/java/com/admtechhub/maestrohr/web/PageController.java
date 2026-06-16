package com.admtechhub.maestrohr.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@Controller
public class PageController {

    // Core pages
    // NOTE: /htmx/dashboard is owned by DashboardController (server-rendered fragment pilot).
    // NOTE: /htmx/employees (+ /htmx/employees/table) is owned by EmployeesController
    //       (server-rendered fragment with search + department/status filters).
    // NOTE: /htmx/departments (+ /htmx/departments/table) is owned by DepartmentsController
    //       (server-rendered fragment with name search). static/departments.html remains
    //       on disk as the legacy fallback until the new fragment is browser-verified.
    // NOTE: /htmx/pay-grades (+ /htmx/pay-grades/table) is owned by PayGradesController
    //       (server-rendered card grid with name search). static/pay-grades.html remains
    //       on disk as the legacy fallback until the new fragment is browser-verified.
    // NOTE: /htmx/leave (+ /htmx/leave/table) is owned by LeaveListController
    //       (server-rendered approval-queue + history table with a status filter and
    //       search). static/leave.html remains on disk as the legacy fallback until the
    //       new fragment is browser-verified.
    // NOTE: /htmx/attendance (+ /htmx/attendance/table) is owned by AttendanceListController
    //       (server-rendered daily roster with a date picker, status filter, and search).
    //       static/attendance.html remains on disk as the legacy fallback until the new
    //       fragment is browser-verified.
    // NOTE: /htmx/payroll (+ /htmx/payroll/table) is owned by PayrollListController and
    //       /htmx/payroll/{id} by PayrollDetailController (server-rendered run history /
    //       approval queue + per-run detail, with a status filter and search).
    //       static/payroll.html and static/payroll-detail.html remain on disk as the
    //       legacy fallbacks (the latter still served via /htmx/payroll-detail below)
    //       until the new fragments are browser-verified.

    @GetMapping("/htmx/reports")
    public String reports(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/reports.html" : "forward:/layout.html";
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