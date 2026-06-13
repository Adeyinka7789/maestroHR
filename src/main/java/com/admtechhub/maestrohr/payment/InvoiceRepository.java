package com.admtechhub.maestrohr.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Idempotency lookup for webhook de-duplication. */
    Optional<Invoice> findByPaystackReference(String paystackReference);

    boolean existsByPaystackReference(String paystackReference);
}
