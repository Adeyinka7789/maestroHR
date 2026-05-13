package com.admtechhub.maestrohr.webhook;

import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.TransferStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class PaystackWebhookController {

    private final PayrollEntryRepository payrollEntryRepository;

    @PostMapping("/paystack")
    public ResponseEntity<String> handlePaystackWebhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader("X-Paystack-Signature") String signature) {

        log.info("Received Paystack webhook");

        // TODO: Verify signature (implement signature verification)

        String event = (String) payload.get("event");
        Map<String, Object> data = (Map<String, Object>) payload.get("data");

        if ("transfer.success".equals(event)) {
            String reference = (String) data.get("reference");
            log.info("Transfer successful: {}", reference);

            // Find and update entry
            // payrollEntryRepository.findByTransferReference(reference)
            //     .ifPresent(entry -> {
            //         entry.setTransferStatus(TransferStatus.SUCCESS);
            //         payrollEntryRepository.save(entry);
            //     });

        } else if ("transfer.failed".equals(event)) {
            String reference = (String) data.get("reference");
            log.error("Transfer failed: {}", reference);

            // Update entry as failed
        }

        return ResponseEntity.ok("OK");
    }
}