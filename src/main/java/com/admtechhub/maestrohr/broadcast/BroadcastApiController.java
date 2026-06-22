package com.admtechhub.maestrohr.broadcast;

import com.admtechhub.maestrohr.broadcast.BroadcastService.UnreadBroadcast;
import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-facing broadcast API (Feature 5). Any authenticated user may read their own unread
 * broadcasts (filtered to their tenant's plan tier) and dismiss them. No special role gate —
 * falls under the default {@code authenticated()} rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
public class BroadcastApiController {

    private final BroadcastService broadcastService;

    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<List<UnreadBroadcast>>> unread() {
        return ResponseEntity.ok(ApiResponse.success("Unread broadcasts retrieved",
                broadcastService.unreadFor(currentEmail())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(@PathVariable UUID id) {
        broadcastService.markRead(id, currentEmail());
        return ResponseEntity.ok(ApiResponse.success("Broadcast marked as read"));
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
