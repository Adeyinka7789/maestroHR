package com.admtechhub.maestrohr.audit;

import com.admtechhub.maestrohr.auth.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditTrailInterceptor implements HandlerInterceptor {

    private final AuditTrailService auditTrailService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("auditStartNanos", System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || path.equals("/error")) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorEmail = authentication != null ? authentication.getName() : "anonymous";
        String tenantIdValue = TenantContext.getCurrentTenant();
        UUID tenantId = null;
        if (tenantIdValue != null && !tenantIdValue.isBlank()) {
            try {
                tenantId = UUID.fromString(tenantIdValue);
            } catch (IllegalArgumentException ignored) {
            }
        }

        String entityId = request.getParameter("employeeId");
        if (entityId == null) {
            entityId = request.getParameter("payrollRunId");
        }

        long durationMs = 0L;
        Object start = request.getAttribute("auditStartNanos");
        if (start instanceof Long startNanos) {
            durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        }

        String details = ex == null
                ? "durationMs=" + durationMs
                : "durationMs=" + durationMs + ", error=" + ex.getClass().getSimpleName();

        auditTrailService.record(
                tenantId,
                actorEmail,
                method + " " + path,
                path.startsWith("/api/reports") ? "REPORT" : "REQUEST",
                entityId,
                path,
                method,
                request.getRemoteAddr(),
                response.getStatus(),
                details
        );
    }
}
