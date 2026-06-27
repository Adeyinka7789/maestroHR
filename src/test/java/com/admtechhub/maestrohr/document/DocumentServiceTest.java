package com.admtechhub.maestrohr.document;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the document upload validation and storage shaping — the logic with branches.
 * Repository access is mocked; no Spring context / DB.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private EmployeeDocumentRepository documentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @InjectMocks private DocumentService documentService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void bindTenant() {
        TenantContext.setCurrentTenant(tenantId.toString());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private void employeeExists() {
        Employee e = Employee.builder().build();
        e.setId(employeeId);
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(e));
    }

    @Test
    void rejectsEmptyFile() {
        MultipartFile empty = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);
        assertThrows(IllegalArgumentException.class,
                () -> documentService.uploadDocument(employeeId, empty, DocumentType.OTHER, null, "hr@x.com"));
        verify(documentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsNullDocumentType() {
        MultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", "data".getBytes());
        assertThrows(IllegalArgumentException.class,
                () -> documentService.uploadDocument(employeeId, file, null, null, "hr@x.com"));
    }

    @Test
    void storesBytesSizeAndSanitizedFileName() {
        employeeExists();
        when(documentRepository.save(org.mockito.ArgumentMatchers.any(EmployeeDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        // Smuggled path components in the original name must be stripped.
        MultipartFile file = new MockMultipartFile("file", "../../etc/passport.pdf", "application/pdf", bytes);

        EmployeeDocument saved = documentService.uploadDocument(
                employeeId, file, DocumentType.PASSPORT, null, "hr@x.com");

        ArgumentCaptor<EmployeeDocument> captor = ArgumentCaptor.forClass(EmployeeDocument.class);
        verify(documentRepository).save(captor.capture());
        EmployeeDocument stored = captor.getValue();

        assertEquals("passport.pdf", stored.getFileName());
        assertEquals(bytes.length, stored.getFileSizeBytes());
        assertEquals(tenantId, stored.getTenantId());
        assertEquals(employeeId, stored.getEmployeeId());
        assertEquals(DocumentType.PASSPORT, stored.getDocumentType());
        assertEquals(saved, stored);
    }
}
