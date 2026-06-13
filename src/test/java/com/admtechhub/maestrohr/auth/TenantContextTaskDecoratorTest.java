package com.admtechhub.maestrohr.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TenantContextTaskDecorator}: the tenant bound on the submitting
 * thread must be visible on the worker thread, must be cleared afterwards (no leakage onto a
 * reused pooled worker), and a missing caller context must not bind anything.
 */
class TenantContextTaskDecoratorTest {

    private final TenantContextTaskDecorator decorator = new TenantContextTaskDecorator();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void propagatesCallerTenantToWorkerThread() throws Exception {
        String tenant = "tenant-A";
        TenantContext.setCurrentTenant(tenant);

        AtomicReference<String> seenOnWorker = new AtomicReference<>();
        AtomicReference<Long> workerThreadId = new AtomicReference<>();
        long callerThreadId = Thread.currentThread().getId();

        // decorate() captures the tenant now, on the submitting thread.
        Runnable decorated = decorator.decorate(() -> {
            seenOnWorker.set(TenantContext.getCurrentTenant());
            workerThreadId.set(Thread.currentThread().getId());
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(decorated).get();
        } finally {
            pool.shutdown();
        }

        assertTrue(workerThreadId.get() != callerThreadId,
                "task must actually run on a different thread for this to be meaningful");
        assertEquals(tenant, seenOnWorker.get(),
                "worker thread must see the tenant captured at submission time");
    }

    @Test
    void clearsContextOnWorkerAfterRun_noLeakToNextTask() throws Exception {
        TenantContext.setCurrentTenant("tenant-A");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> seenBySecondTask = new AtomicReference<>("unset");
        try {
            // First task runs with tenant-A bound on the (single, reused) worker thread.
            pool.submit(decorator.decorate(() -> { })).get();

            // A second task submitted with NO caller tenant must not observe tenant-A
            // left behind on the pooled worker thread.
            TenantContext.clear();
            pool.submit(decorator.decorate(
                    () -> seenBySecondTask.set(TenantContext.getCurrentTenant()))).get();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertNull(seenBySecondTask.get(),
                "a reused worker must not leak the previous task's tenant");
    }

    @Test
    void clearsContextEvenWhenTaskThrows() throws Exception {
        TenantContext.setCurrentTenant("tenant-A");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> seenBySecondTask = new AtomicReference<>("unset");
        try {
            try {
                pool.submit(decorator.decorate(() -> {
                    throw new RuntimeException("boom");
                })).get();
            } catch (Exception expected) {
                // The task throws; the decorator's finally must still clear the worker.
            }

            TenantContext.clear();
            pool.submit(decorator.decorate(
                    () -> seenBySecondTask.set(TenantContext.getCurrentTenant()))).get();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertNull(seenBySecondTask.get(),
                "worker tenant must be cleared even when the task throws");
    }

    @Test
    void noCallerTenant_bindsNothingOnWorker() throws Exception {
        TenantContext.clear();

        AtomicReference<String> seenOnWorker = new AtomicReference<>("unset");
        Runnable decorated = decorator.decorate(
                () -> seenOnWorker.set(TenantContext.getCurrentTenant()));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(decorated).get();
        } finally {
            pool.shutdown();
        }

        assertNull(seenOnWorker.get(),
                "with no caller tenant, nothing should be bound on the worker");
    }
}
