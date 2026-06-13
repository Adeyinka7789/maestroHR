package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the wiring end-to-end: the single {@link org.springframework.core.task.TaskDecorator}
 * bean from {@link AsyncConfig} is picked up by Spring Boot's
 * {@link TaskExecutionAutoConfiguration} and applied to the {@code applicationTaskExecutor} —
 * the very executor that backs {@code @Async} methods. A task submitted while a tenant is
 * bound on the caller thread therefore observes that tenant on the worker thread.
 */
class AsyncTenantPropagationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    TaskExecutionAutoConfiguration.class))
            .withUserConfiguration(AsyncConfig.class);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void applicationTaskExecutor_propagatesTenantToWorkerThread() {
        runner.run(context -> {
            AsyncTaskExecutor executor =
                    context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);

            TenantContext.setCurrentTenant("tenant-Z");

            AtomicReference<String> seenOnWorker = new AtomicReference<>();
            AtomicReference<Long> workerThreadId = new AtomicReference<>();
            long callerThreadId = Thread.currentThread().getId();

            executor.submit(() -> {
                seenOnWorker.set(TenantContext.getCurrentTenant());
                workerThreadId.set(Thread.currentThread().getId());
            }).get();

            assertThat(workerThreadId.get())
                    .as("task must run on a worker thread, not the caller")
                    .isNotEqualTo(callerThreadId);
            assertThat(seenOnWorker.get())
                    .as("the @Async executor must propagate the caller's tenant")
                    .isEqualTo("tenant-Z");
        });
    }

    @Test
    void workerThread_isClearedBetweenTasks() {
        // Pin the pool to a single thread so the second task is guaranteed to reuse the
        // same worker the first task ran on — that is what makes leakage observable.
        runner.withPropertyValues(
                        "spring.task.execution.pool.core-size=1",
                        "spring.task.execution.pool.max-size=1")
                .run(context -> {
                    AsyncTaskExecutor executor =
                            context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);

                    // First task: bound to tenant-Z.
                    TenantContext.setCurrentTenant("tenant-Z");
                    executor.submit(() -> { }).get();

                    // Second task: no caller tenant. The same worker is reused, so this
                    // proves the decorator cleared tenant-Z afterwards.
                    TenantContext.clear();
                    AtomicReference<String> seenBySecondTask = new AtomicReference<>("unset");
                    executor.submit(() ->
                            seenBySecondTask.set(TenantContext.getCurrentTenant())).get();

                    assertThat(seenBySecondTask.get())
                            .as("a reused worker must not leak the previous task's tenant")
                            .isNull();
                });
    }
}
