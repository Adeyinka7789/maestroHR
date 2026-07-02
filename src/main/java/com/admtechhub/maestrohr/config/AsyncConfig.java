package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.TenantContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

@Configuration
public class AsyncConfig {

    @Bean
    public TaskDecorator tenantContextTaskDecorator() {
        return new TenantContextTaskDecorator();
    }
}