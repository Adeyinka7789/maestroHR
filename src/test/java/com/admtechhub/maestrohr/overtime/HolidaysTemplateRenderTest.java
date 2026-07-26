package com.admtechhub.maestrohr.overtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render test for templates/holidays.html — validates the add form, the calendar table (including
 * {@code #temporals} date formatting), and the empty state. Renders through a mock web exchange so
 * {@code @{/...}} links resolve.
 */
@SpringBootTest
class HolidaysTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private String render(Object holidays) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable("holidays", holidays);
        return templateEngine.process("holidays", Set.of("content"), ctx);
    }

    @Test
    void rendersHolidayRowWithActions() {
        PublicHoliday h = PublicHoliday.builder()
                .holidayDate(LocalDate.of(2026, 10, 1)).name("Independence Day").active(true).build();
        h.setId(UUID.randomUUID());

        String html = render(List.of(h));

        assertTrue(html.contains("Public Holidays"), "page heading");
        assertTrue(html.contains("Independence Day"), "holiday name");
        assertTrue(html.contains("2026"), "formatted date includes the year");
        assertTrue(html.contains("/htmx/holidays/" + h.getId() + "/toggle"), "toggle endpoint wired");
        assertTrue(html.contains("/htmx/holidays/" + h.getId() + "/delete"), "delete endpoint wired");
    }

    @Test
    void rendersEmptyState() {
        String html = render(List.of());
        assertTrue(html.contains("No holidays configured"), "empty state");
    }
}
