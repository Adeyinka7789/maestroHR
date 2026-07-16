package com.admtechhub.maestrohr.subscription;
import com.admtechhub.maestrohr.flags.*;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformFlagSeeder implements ApplicationRunner {

    private final PlatformFlagRepository flagRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (SubscriptionFeature feature : SubscriptionFeature.values()) {
            String name = feature.name();
            if (flagRepository.findByName(name).isEmpty()) {
                flagRepository.save(PlatformFlag.builder()
                        .name(name)
                        .enabled(true)
                        .description(name.replace('_', ' ').toLowerCase())
                        .updatedBy("system")
                        .build());
                log.info("Seeded platform flag: {}", name);
            }
        }
    }
}
