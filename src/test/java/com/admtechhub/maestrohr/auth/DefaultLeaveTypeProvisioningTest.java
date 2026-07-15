package com.admtechhub.maestrohr.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the "Apply for Leave picker is empty in production" bug: registration must
 * seed the default leave types (they were previously never created because
 * {@code LeaveService.createDefaultLeaveTypes} was dead code). Registers a company and asserts the
 * new tenant has the 7 defaults, read through the privileged datasource (leave_types is
 * RLS/@SQLRestriction-scoped, so a plain JPA read with no tenant session would see nothing).
 */
@SpringBootTest
class DefaultLeaveTypeProvisioningTest {

    @Autowired private AuthService authService;

    @Autowired
    @Qualifier("privilegedJdbcTemplate")
    private JdbcTemplate privilegedJdbc;

    @Test
    void register_seedsTheSevenDefaultLeaveTypes() {
        String email = "leaveseed-" + UUID.randomUUID() + "@example.test";
        AuthRequest.Register reg = new AuthRequest.Register();
        reg.setCompanyName("LeaveSeed " + UUID.randomUUID().toString().substring(0, 8));
        reg.setIndustry("Technology");
        reg.setCompanySize("1-10");
        reg.setAdminEmail(email);
        reg.setPassword("password123");

        UUID tenantId = authService.register(reg).getTenantId();
        try {
            Integer count = privilegedJdbc.queryForObject(
                    "SELECT COUNT(*) FROM leave_types WHERE tenant_id = ?", Integer.class, tenantId);
            assertEquals(7, count, "a freshly-registered tenant is seeded with 7 default leave types");

            Integer annual = privilegedJdbc.queryForObject(
                    "SELECT COUNT(*) FROM leave_types WHERE tenant_id = ? AND code = 'ANNUAL'",
                    Integer.class, tenantId);
            assertEquals(1, annual, "Annual Leave is one of the defaults");
        } finally {
            // Cascades to leave_types / users / subscription (V55 made every tenant FK ON DELETE CASCADE).
            privilegedJdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        }
    }
}
