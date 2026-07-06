package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.config.TermiiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TermiiClient {

    private final TermiiConfig termiiConfig;
    private final RestTemplate restTemplate;

    /** Placeholder value shipped in .env for local/dev environments with no real Termii account. */
    private static final String PLACEHOLDER_API_KEY = "termii_key_placeholder";

    public void sendSms(String phoneNumber, String message) {
        String apiKey = termiiConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank() || PLACEHOLDER_API_KEY.equals(apiKey)) {
            log.debug("Skipping SMS to {}: Termii API key not configured", phoneNumber);
            return;
        }

        try {
            String url = termiiConfig.getBaseUrl() + "/sms/send";

            Map<String, Object> request = Map.of(
                    "to", phoneNumber,
                    "from", termiiConfig.getSenderId(),
                    "sms", message,
                    "type", "plain",
                    "channel", "generic",
                    "api_key", termiiConfig.getApiKey()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            restTemplate.postForObject(url, entity, Map.class);
            log.info("SMS sent to {}: {}", phoneNumber, message);

        } catch (Exception e) {
            log.error("Failed to send SMS to {}: {}", phoneNumber, e.getMessage());
        }
    }
}