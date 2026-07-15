package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.AuthRequest;
import com.admtechhub.maestrohr.auth.AuthResponse;
import com.admtechhub.maestrohr.auth.AuthService;
import com.admtechhub.maestrohr.platform.DeletedRecordsQueries;
import com.admtechhub.maestrohr.platform.DeletedRecordsQueries.DeletedType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the self-service company deletion flow. Seeds a real company via
 * {@link AuthService#register} (which creates a tenant + its SYSTEM_ADMIN owner), then drives
 * {@link AccountService} through the confirmation gate and verifies the soft-delete: the company
 * disappears from the caller's live companies and shows up on the super-admin trash page as a
 * TENANT row. Cleans up by purging the trashed tenant (cascades away all its data).
 */
@SpringBootTest
class AccountServiceTest {

    @Autowired private AuthService authService;
    @Autowired private AccountService accountService;
    @Autowired private DeletedRecordsQueries deletedRecordsQueries;

    private AuthRequest.Register registration(String company, String email, String password) {
        AuthRequest.Register r = new AuthRequest.Register();
        r.setCompanyName(company);
        r.setIndustry("Technology");
        r.setCompanySize("1-10");
        r.setAdminEmail(email);
        r.setPassword(password);
        return r;
    }

    @Test
    void deleteCompany_softDeletes_onlyAfterPasswordAndNameConfirm() {
        String email = "owner-" + UUID.randomUUID() + "@example.test";
        String company = "AcctDel " + UUID.randomUUID().toString().substring(0, 8);
        AuthResponse reg = authService.register(registration(company, email, "password123"));
        UUID tenantId = reg.getTenantId();

        try {
            // Wrong password is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> accountService.deleteCompany(email, tenantId, company, "wrong-password"));
            // Wrong company name is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> accountService.deleteCompany(email, tenantId, "Not The Company", "password123"));
            // Sole member cannot "leave" — they must delete instead.
            assertThrows(IllegalArgumentException.class,
                    () -> accountService.leaveCompany(email, tenantId));

            // Still live before deletion.
            assertEquals(1, accountService.listCompanies(email, null).companies().size(),
                    "company is listed before deletion");

            // Correct password + exact name → soft-delete.
            String deletedName = accountService.deleteCompany(email, tenantId, company, "password123");
            assertEquals(company, deletedName);

            // Gone from the caller's live companies (hidden from login/switcher too).
            assertTrue(accountService.listCompanies(email, null).companies().isEmpty(),
                    "soft-deleted company no longer appears for its owner");

            // Present on the super-admin trash page as a restorable TENANT row.
            boolean inTrash = deletedRecordsQueries.findAllDeleted().stream()
                    .anyMatch(r -> "TENANT".equals(r.type()) && company.equals(r.label()));
            assertTrue(inTrash, "soft-deleted company appears on the super-admin trash page");
        } finally {
            // Clean up the dev DB: purge-now cascades the tenant and all its data away.
            deletedRecordsQueries.purgeNow(DeletedType.TENANT, tenantId);
        }
    }
}
