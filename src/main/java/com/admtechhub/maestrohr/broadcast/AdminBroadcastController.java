package com.admtechhub.maestrohr.broadcast;

import com.admtechhub.maestrohr.broadcast.BroadcastService.BroadcastSummary;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.platform.AuditTrailWrites;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SUPER_ADMIN REST API for composing and listing platform broadcasts (Feature 5).
 *
 * <p>Authorization: {@code /api/admin/**} is SUPER_ADMIN-gated at the URL layer in SecurityConfig;
 * {@code @PreAuthorize} is the method-level backstop per the project pattern.
 */
@RestController
@RequestMapping("/api/admin/broadcasts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminBroadcastController {

    private final BroadcastService broadcastService;
    private final AuditTrailWrites auditTrailWrites;

    @PostMapping
    public ResponseEntity<ApiResponse<BroadcastSummary>> create(
            @RequestBody CreateBroadcastRequest request,
            HttpServletRequest http) {

        if (request == null || isBlank(request.title()) || isBlank(request.body())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Title and body are required"));
        }

        String actor = actorEmail();
        Broadcast saved = broadcastService.create(
                request.title(), request.body(), request.targetPlans(), actor);

        // Platform-global action → no tenant_id. Tagged BROADCAST_SENT for the audit trail.
        auditTrailWrites.insert(null, actor, "BROADCAST_SENT", "broadcast", saved.getId().toString(),
                http.getRequestURI(), "POST", http.getRemoteAddr(), 200,
                "target=" + saved.getTargetPlans());

        return ResponseEntity.ok(ApiResponse.success("Broadcast sent",
                new BroadcastSummary(saved.getId(), saved.getTitle(), saved.getBody(),
                        saved.getTargetPlans(), saved.getCreatedBy(), saved.getCreatedAt(), 0L)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BroadcastSummary>>> list() {
        return ResponseEntity.ok(ApiResponse.success("Broadcasts retrieved",
                broadcastService.listAllWithReadCounts()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String actorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public record CreateBroadcastRequest(String title, String body, String targetPlans) {
    }
}
