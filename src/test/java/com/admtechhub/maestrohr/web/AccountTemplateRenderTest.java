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
 * Render test for templates/account.html — processes the {@code companies} fragment through the
 * Thymeleaf engine with a hand-built {@link AccountView}, validating the per-company Leave/Delete
 * affordances, the sole-member hint, the delete confirmation inputs, the banners, and the empty
 * state without the MVC stack or a tenant session. Mirrors {@link AttendanceAnalyticsTemplateRenderTest}.
 */
@SpringBootTest
class AccountTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private String render(AccountView view, String successMessage, String errorMessage) {
        Context ctx = new Context();
        ctx.setVariable("view", view);
        if (successMessage != null) {
            ctx.setVariable("successMessage", successMessage);
        }
        if (errorMessage != null) {
            ctx.setVariable("errorMessage", errorMessage);
        }
        return templateEngine.process("account", Set.of("companies"), ctx);
    }

    @Test
    void rendersLeaveForMultiMember_andDeleteForOwner() {
        // Current company: owner + sole member → Delete shown, Leave hidden (sole-member hint instead).
        AccountView.CompanyRow ownedCurrent = new AccountView.CompanyRow(
                UUID.randomUUID().toString(), "Acme Ltd", "System Admin", true, true, 1, true);
        // Other company: plain member, several people → Leave shown, no Delete.
        AccountView.CompanyRow memberOther = new AccountView.CompanyRow(
                UUID.randomUUID().toString(), "Globex Inc", "Hr Admin", false, false, 4, false);

        String html = render(new AccountView(List.of(ownedCurrent, memberOther), true), null, null);

        assertTrue(html.contains("Your Companies"), "section heading");
        assertTrue(html.contains("Acme Ltd"), "owned company name");
        assertTrue(html.contains("Globex Inc"), "member company name");
        assertTrue(html.contains("Current"), "current-company badge");
        assertTrue(html.contains("Delete company"), "owner sees delete affordance");
        assertTrue(html.contains("/leave"), "leave endpoint wired for the multi-member company");
        assertTrue(html.contains("/delete"), "delete endpoint wired for the owned company");
        assertTrue(html.contains("name=\"password\""), "delete confirm requires a password");
        assertTrue(html.contains("name=\"companyName\""), "delete confirm requires typing the company name");
        assertTrue(html.contains("only member"), "sole-member hint shown instead of Leave");
    }

    @Test
    void nonOwnerHasNoDeleteButton() {
        AccountView.CompanyRow memberOnly = new AccountView.CompanyRow(
                UUID.randomUUID().toString(), "Globex Inc", "Finance Officer", false, false, 3, false);

        String html = render(new AccountView(List.of(memberOnly), false), null, null);

        assertFalse(html.contains("Delete company"), "a non-owner never sees the delete affordance");
        assertTrue(html.contains("Leave"), "a non-owner can still leave");
    }

    @Test
    void rendersBannersAndEmptyState() {
        String success = render(new AccountView(List.of(), false), "You have left \"Acme\".", null);
        assertTrue(success.contains("You have left"), "success banner");
        assertTrue(success.contains("No companies found"), "empty state");

        String error = render(new AccountView(List.of(), false), null, "Your password is incorrect.");
        assertTrue(error.contains("Your password is incorrect."), "error banner");
    }
}
