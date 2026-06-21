package com.admtechhub.maestrohr.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render test for templates/leave.html. Processes the two Thymeleaf fragments
 * ({@code content} and {@code table}) directly through the engine with a hand-built
 * {@link LeaveListView}, so it validates the markup parses and every expression
 * evaluates (fragment names, the status-chip {@code th:classappend}/count rendering,
 * the hidden status carrier, the status-badge {@code th:classappend}, the empty
 * {@code th:if}) without needing security, the MVC stack, or a tenant context.
 * Pairs with the controller wiring exercised at runtime; a template typo would
 * otherwise only surface in the browser.
 */
@SpringBootTest
class LeaveTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private static List<LeaveListView.StatusChip> chips(String active) {
        return List.of(
                new LeaveListView.StatusChip("", "All", 9, active == null),
                new LeaveListView.StatusChip("PENDING", "Pending", 3, "PENDING".equals(active)),
                new LeaveListView.StatusChip("APPROVED", "Approved", 4, "APPROVED".equals(active)),
                new LeaveListView.StatusChip("REJECTED", "Rejected", 2, "REJECTED".equals(active)));
    }

    private static List<LeaveListView.EmployeeOption> employees() {
        return List.of(
                new LeaveListView.EmployeeOption(UUID.randomUUID(), "Tayo Shonibare (E001)"),
                new LeaveListView.EmployeeOption(UUID.randomUUID(), "Amaka Obi (E002)"));
    }

    private static List<LeaveListView.LeaveTypeOption> leaveTypes() {
        return List.of(
                new LeaveListView.LeaveTypeOption(UUID.randomUUID(), "Annual Leave"),
                new LeaveListView.LeaveTypeOption(UUID.randomUUID(), "Sick Leave"));
    }

    private static LeaveListView viewWithRows() {
        return viewWithRows(employees(), null, false, true);
    }

    private static LeaveListView viewWithRows(List<LeaveListView.EmployeeOption> employees,
                                              UUID currentEmployeeId, boolean isEmployeeRole,
                                              boolean isHrRole) {
        LeaveListView.Row pending = new LeaveListView.Row(
                UUID.randomUUID(), "Tayo Shonibare", "TS", "Annual Leave",
                "02 Jun 2025", "06 Jun 2025", 5, "Family event",
                "PENDING", "Pending", "warn", "01 Jun 2025");
        LeaveListView.Row approved = new LeaveListView.Row(
                UUID.randomUUID(), "Amaka Obi", "AO", "Sick Leave",
                "10 May 2025", "11 May 2025", 2, "Flu",
                "APPROVED", "Approved", "success", "09 May 2025");
        return new LeaveListView(List.of(pending, approved), 2, null, "PENDING", chips("PENDING"),
                employees, leaveTypes(), currentEmployeeId, isEmployeeRole, isHrRole);
    }

    @Test
    void contentFragment_rendersHeaderSearchAndResults() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("leave", Set.of("content"), ctx);

        assertTrue(html.contains("Leave Management"), "header title");
        assertTrue(html.contains("/htmx/leave/table"), "search swaps the table fragment");
        assertTrue(html.contains("Tayo Shonibare"), "first request row (results inserted into content)");
        assertTrue(html.contains("Annual Leave"), "leave type rendered");
    }

    @Test
    void contentFragment_rendersApplyFormWithFullRosterForNonEmployee() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("leave", Set.of("content"), ctx);

        assertTrue(html.contains("Apply for Leave"), "apply form trigger");
        assertTrue(html.contains("/htmx/leave/apply"), "apply form posts to the apply endpoint");
        assertTrue(html.contains("name=\"leaveTypeId\""), "leave type picker present");
        assertTrue(html.contains("Sick Leave"), "leave type option rendered");
        assertTrue(html.contains("Select an employee…"), "full roster picker (non-employee role)");
        assertFalse(html.contains("<select disabled"), "employee picker is not locked for HR/admin");
    }

    @Test
    void contentFragment_employeeRole_locksEmployeePicker() {
        UUID self = UUID.randomUUID();
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows(
                List.of(new LeaveListView.EmployeeOption(self, "Tayo Shonibare (E001)")), self, true, false));

        String html = templateEngine.process("leave", Set.of("content"), ctx);

        assertTrue(html.contains("<select disabled"), "employee picker is disabled (locked) for an EMPLOYEE");
        assertTrue(html.contains("name=\"employeeId\""), "hidden employeeId carrier present");
        assertTrue(html.contains(self.toString()), "hidden carrier holds the session employee id");
        assertFalse(html.contains("Select an employee…"), "no free roster picker for an EMPLOYEE");
    }

    @Test
    void contentFragment_rendersApplyBanners() {
        Context successCtx = new Context();
        successCtx.setVariable("view", viewWithRows());
        successCtx.setVariable("success", "Leave request submitted.");
        String successHtml = templateEngine.process("leave", Set.of("content"), successCtx);
        assertTrue(successHtml.contains("Leave request submitted."), "success banner rendered");

        Context errorCtx = new Context();
        errorCtx.setVariable("view", viewWithRows());
        errorCtx.setVariable("formError", "Insufficient leave balance.");
        String errorHtml = templateEngine.process("leave", Set.of("content"), errorCtx);
        assertTrue(errorHtml.contains("Insufficient leave balance."), "formError banner rendered");
    }

    @Test
    void tableFragment_rendersChipsCountsAndStatusBadges() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("leave", Set.of("table"), ctx);

        assertTrue(html.contains("All"), "All chip");
        assertTrue(html.contains("Approved"), "Approved chip / badge");
        assertTrue(html.contains("Rejected"), "Rejected chip");
        assertTrue(html.contains(">9<"), "All chip tenant-wide count");
        assertTrue(html.contains("bg-primary text-white border-primary"), "selected chip styling (Pending)");
        assertTrue(html.contains("bg-secondary-container"), "approved row uses the success badge colour");
        assertTrue(html.contains("name=\"status\""), "hidden status carrier present");
        assertTrue(html.contains("Family event"), "reason rendered");
        assertFalse(html.contains("No leave requests found"), "non-empty view hides the empty state");
    }

    @Test
    void tableFragment_emptyView_rendersEmptyState() {
        Context ctx = new Context();
        ctx.setVariable("view", new LeaveListView(List.of(), 0, "missing", null, chips(null),
                List.of(), List.of(), null, false, true));

        String html = templateEngine.process("leave", Set.of("table"), ctx);

        assertTrue(html.contains("No leave requests found"), "empty state shown when there are no rows");
        assertTrue(html.contains("bg-primary text-white border-primary"), "All chip is selected when status is null");
    }
}
