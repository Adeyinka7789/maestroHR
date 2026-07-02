package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.TenantContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Wires {@link TenantContextTaskDecorator} into the application's async executor.
 *
 * <p>Spring Boot's {@code TaskExecutionAutoConfiguration} applies a single
 * {@link TaskDecorator} bean to the auto-configured {@code applicationTaskExecutor}, which is
 * the executor {@code @Async} methods run on. Exposing the decorator as the one
 * {@code TaskDecorator} bean is therefore enough to make every {@code @Async} call propagate
 * {@link com.admtechhub.maestrohr.auth.TenantContext} to its worker thread — no per-call-site
 * changes required, and any future {@code @Async} method is covered automatically.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public TaskDecorator tenantContextTaskDecorator() {
        return new TenantContextTaskDecorator();
    }

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("employee-async-");
        executor.initialize();
        return executor;
        }
}
