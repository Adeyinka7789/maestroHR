package com.admtechhub.maestrohr.tenant;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenant/logo")
@RequiredArgsConstructor
@Slf4j
public class TenantLogoController {

    private final TenantRepository tenantRepository;

    @Value("${logo.upload.dir:uploads/logos}")
    private String uploadDir;

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadLogo(@RequestParam("file") MultipartFile file) {
        try {
            UUID tenantId = getCurrentTenantId();
            Tenant tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

            // Create upload directory if not exists
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Generate unique filename
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            String fileName = "logo_" + tenantId.toString() + extension;
            Path filePath = uploadPath.resolve(fileName);

            // Save file
            Files.copy(file.getInputStream(), filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Update tenant with logo URL
            String logoUrl = "/uploads/logos/" + fileName;
            tenant.setLogoUrl(logoUrl);
            tenant.setLogoFileName(fileName);
            tenantRepository.save(tenant);

            Map<String, String> response = new HashMap<>();
            response.put("logoUrl", logoUrl);
            response.put("message", "Logo uploaded successfully");

            return ResponseEntity.ok(ApiResponse.success("Logo uploaded", response));

        } catch (IOException e) {
            log.error("Failed to upload logo", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to upload logo"));
        }
    }

    @DeleteMapping("/delete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteLogo() {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        if (tenant.getLogoUrl() != null) {
            try {
                Path filePath = Paths.get(uploadDir, tenant.getLogoFileName());
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.warn("Failed to delete logo file", e);
            }
            tenant.setLogoUrl(null);
            tenant.setLogoFileName(null);
            tenantRepository.save(tenant);
        }

        return ResponseEntity.ok(ApiResponse.success("Logo deleted", null));
    }

    private UUID getCurrentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}