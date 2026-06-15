package com.admtechhub.maestrohr.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render test for templates/attendance-self.html (Step D — employee self check-in/out).
 * Processes the {@code content} and {@code card} fragments directly through the Thymeleaf
 * engine with a hand-built {@link AttendanceSelfView}, so it validates the markup parses
 * and every expression evaluates (the status badge {@code th:classappend}, the time grid,
 * the conditional notes input vs. read-only notes, the canCheckIn/canCheckOut button gating,
 * and the {@code ${error}} banner) without needing security, the MVC stack, or a tenant
 * context. Mirrors {@link AttendanceTemplateRenderTest}.
 */
@SpringBootTest
class AttendanceSelfTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private static AttendanceSelfView notCheckedIn() {
        return new AttendanceSelfView(
                "Monday, 15 June 2026", "Jane Doe",
                "Not checked in yet", "neutral",
                "—", "—", "—", null,
                true, false);
    }

    private static AttendanceSelfView checkedIn() {
        return new AttendanceSelfView(
                "Monday, 15 June 2026", "Jane Doe",
                "Checked in", "success",
                "09:05", "—", "—", "At the Lagos office",
                false, true);
    }

    private static AttendanceSelfView checkedOut() {
        return new AttendanceSelfView(
                "Monday, 15 June 2026", "Jane Doe",
                "Checked out", "neutral",
                "09:05", "17:30", "8.42", "At the Lagos office",
                false, false);
    }

    @Test
    void contentFragment_rendersHeaderAndCardContainer() {
        Context ctx = new Context();
        ctx.setVariable("view", notCheckedIn());

        String html = templateEngine.process("attendance-self", Set.of("content"), ctx);

        assertTrue(html.contains("My Attendance"), "header title");
        assertTrue(html.contains("id=\"attendance-self-card\""), "card swap container present");
        assertTrue(html.contains("Check In"), "card is inserted into content");
    }

    @Test
    void cardFragment_notCheckedIn_showsCheckInButtonAndEditableNotes() {
        Context ctx = new Context();
        ctx.setVariable("view", notCheckedIn());

        String html = templateEngine.process("attendance-self", Set.of("card"), ctx);

        assertTrue(html.contains("Not checked in yet"), "status label");
        assertTrue(html.contains("/htmx/attendance/check-in"), "check-in action present");
        assertTrue(html.contains("name=\"notes\""), "editable notes input present");
        assertFalse(html.contains("/htmx/attendance/check-out"), "no check-out before check-in");
        assertFalse(html.contains("You're all done"), "not done while a check-in is available");
    }

    @Test
    void cardFragment_checkedIn_showsCheckOutButtonAndPrefilledNotes() {
        Context ctx = new Context();
        ctx.setVariable("view", checkedIn());

        String html = templateEngine.process("attendance-self", Set.of("card"), ctx);

        assertTrue(html.contains("Checked in"), "status label");
        assertTrue(html.contains("bg-secondary-container"), "success badge colour");
        assertTrue(html.contains("/htmx/attendance/check-out"), "check-out action present");
        assertTrue(html.contains("09:05"), "clock-in time rendered");
        assertTrue(html.contains("At the Lagos office"), "notes carried into the editable field");
        assertFalse(html.contains("/htmx/attendance/check-in"), "no check-in once checked in");
    }

    @Test
    void cardFragment_checkedOut_showsDoneStateAndReadOnlyNotes() {
        Context ctx = new Context();
        ctx.setVariable("view", checkedOut());

        String html = templateEngine.process("attendance-self", Set.of("card"), ctx);

        assertTrue(html.contains("Checked out"), "status label");
        assertTrue(html.contains("17:30"), "clock-out time rendered");
        assertTrue(html.contains("8.42"), "hours rendered");
        assertTrue(html.contains("You're all done for today."), "done state shown");
        assertTrue(html.contains("At the Lagos office"), "recorded notes shown read-only");
        assertFalse(html.contains("name=\"notes\""), "no editable notes input once done");
        assertFalse(html.contains("/htmx/attendance/check-in"), "no actions once done");
        assertFalse(html.contains("/htmx/attendance/check-out"), "no actions once done");
    }

    @Test
    void cardFragment_actionFailure_rendersErrorBanner() {
        Context ctx = new Context();
        ctx.setVariable("view", checkedIn());
        ctx.setVariable("error", "Already checked in today");

        String html = templateEngine.process("attendance-self", Set.of("card"), ctx);

        assertTrue(html.contains("Already checked in today"), "error banner message rendered");
        assertTrue(html.contains("bg-error-container"), "error banner uses the error colour");
    }
}
