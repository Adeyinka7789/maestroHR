package com.admtechhub.maestrohr.document;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** JSON view of a stored document (metadata only — bytes are fetched via the download URL). */
@Data
@AllArgsConstructor
public class DocumentResponseDTO {
    private UUID id;
    private UUID employeeId;
    private DocumentType documentType;
    private String fileName;
    private long fileSizeBytes;
    private String mimeType;
    private LocalDate expiryDate;
    private String uploadedBy;
    private OffsetDateTime createdAt;

    public static DocumentResponseDTO from(EmployeeDocumentSummary s) {
        return new DocumentResponseDTO(
                s.getId(), s.getEmployeeId(), s.getDocumentType(), s.getFileName(),
                s.getFileSizeBytes(), s.getMimeType(), s.getExpiryDate(), s.getUploadedBy(),
                s.getCreatedAt());
    }
}
