package com.admtechhub.maestrohr;

import com.admtechhub.maestrohr.tenant.PricingService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class MaestrohrApplication {

    private final PricingService pricingService;

    public MaestrohrApplication(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    public static void main(String[] args) {
        SpringApplication.run(MaestrohrApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializePricing() {
        pricingService.initializeDefaultPricing();
    }
}