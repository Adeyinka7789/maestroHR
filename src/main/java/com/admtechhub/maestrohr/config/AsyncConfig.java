package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.TenantContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

import java.time.Clock;

@Configuration
public class AsyncConfig {

    @Bean
    public TaskDecorator tenantContextTaskDecorator() {
        return new TenantContextTaskDecorator();
    }

    /** Injectable system clock so time-dependent services (e.g. AttendanceService) can be tested deterministically. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}