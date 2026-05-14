package com.admtechhub.maestrohr.webhook;

import com.admtechhub.maestrohr.payment.PaymentWebhookService;
import com.admtechhub.maestrohr.payment.dto.PaystackWebhookPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class PaystackWebhookController {

    private final PaymentWebhookService paymentWebhookService;
    private final ObjectMapper objectMapper;

    @Value("${paystack.secret-key}")
    private String paystackSecretKey;

    @Value("${paystack.webhook.verify-signature:false}")
    private boolean verifySignatureEnabled;

    @PostMapping("/paystack")
    public ResponseEntity<String> handlePaystackWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Paystack-Signature", required = false) String signature) {

        log.info("Received Paystack webhook");

        // Only verify signature if enabled
        if (verifySignatureEnabled && !verifySignature(payload, signature)) {
            log.error("Invalid webhook signature");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        try {
            PaystackWebhookPayload webhookPayload = objectMapper.readValue(payload, PaystackWebhookPayload.class);
            String event = webhookPayload.getEvent();

            log.info("Processing event: {}", event);

            switch (event) {
                case "subscription.create":
                    paymentWebhookService.handleSubscriptionCreate(webhookPayload);
                    break;
                case "charge.success":
                    paymentWebhookService.handleChargeSuccess(webhookPayload);
                    break;
                case "subscription.disable":
                    paymentWebhookService.handleSubscriptionDisable(webhookPayload);
                    break;
                case "invoice.create":
                    paymentWebhookService.handleInvoiceCreate(webhookPayload);
                    break;
                case "invoice.payment_failed":
                    paymentWebhookService.handleInvoicePaymentFailed(webhookPayload);
                    break;
                case "transfer.success":
                    log.info("Transfer successful: {}", webhookPayload.getData().getReference());
                    break;
                case "transfer.failed":
                    log.error("Transfer failed: {}", webhookPayload.getData().getReference());
                    break;
                default:
                    log.debug("Unhandled event type: {}", event);
            }

            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Failed to process webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Error processing webhook");
        }
    }

    /**
     * Verify Paystack webhook signature for security
     */
    private boolean verifySignature(String payload, String signature) {
        if (signature == null) {
            return false;
        }

        try {
            Mac sha512Hmac = Mac.getInstance("HmacSHA512");
            SecretKeySpec keySpec = new SecretKeySpec(
                    paystackSecretKey.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA512"
            );
            sha512Hmac.init(keySpec);

            byte[] macData = sha512Hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedSignature = Base64.getEncoder().encodeToString(macData);

            return computedSignature.equals(signature);

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }
}