package com.admtechhub.maestrohr.auth;

import org.springframework.core.task.TaskDecorator;

/**
 * Propagates {@link TenantContext} across the {@code @Async} thread boundary.
 *
 * <p>{@code TenantContext} is a plain {@link ThreadLocal}, so it does not follow a task
 * submitted to a worker thread. Without this decorator, async work (e.g. notification
 * sending) runs with no tenant bound: {@code DataSourceConfig} then writes an empty
 * {@code app.current_tenant}, RLS sees no tenant, and tenant-scoped rows are read as empty
 * or written with a {@code null} tenant_id.
 *
 * <p>This decorator captures the submitting thread's tenant at submission time and binds it
 * on the worker thread for the duration of the task, clearing it in a {@code finally} so a
 * pooled worker never leaks one tenant's context into the next task. The clear restores the
 * worker to a clean state rather than the caller's value, because the worker is reused.
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Captured on the submitting thread, at submission time.
        String tenant = TenantContext.getCurrentTenant();
        return () -> {
            try {
                if (tenant != null && !tenant.isBlank()) {
                    TenantContext.setCurrentTenant(tenant);
                }
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
