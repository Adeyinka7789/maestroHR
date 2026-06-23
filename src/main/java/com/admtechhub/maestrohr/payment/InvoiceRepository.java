package com.admtechhub.maestrohr.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Idempotency lookup for webhook de-duplication. */
    Optional<Invoice> findByPaystackReference(String paystackReference);

    /**
     * The five most recent invoices for the current tenant, newest first. Backs the
     * FINANCE_OFFICER dashboard "Recent Invoices" panel. Tenant-scoped by the
     * {@code @SQLRestriction} on {@link Invoice}, so no tenant param is needed.
     */
    List<Invoice> findTop5ByOrderByCreatedAtDesc();

    boolean existsByPaystackReference(String paystackReference);

    /**
     * Resolve the owning tenant of an invoice from its Paystack reference, WITHOUT a
     * tenant session bound. A Paystack webhook arrives with no tenant context, so the
     * normal {@code @SQLRestriction}-scoped lookups would return nothing. This native
     * query is not subject to {@code @SQLRestriction}; the result is used to bind the
     * tenant session before any scoped reads/writes are performed.
     */
    @Query(value = "SELECT tenant_id FROM invoices WHERE paystack_reference = :reference",
            nativeQuery = true)
    Optional<UUID> findTenantIdByPaystackReference(@Param("reference") String reference);
}
