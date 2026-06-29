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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    // Test 1 — upload exceeds 10 MB limit
    @Test
    void uploadDocument_exceedsMaxSize_throws() {
        byte[] big = new byte[(int) DocumentService.MAX_FILE_SIZE_BYTES + 1];
        MultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", big);

        assertThrows(IllegalArgumentException.class,
                () -> documentService.uploadDocument(employeeId, file, DocumentType.OTHER, null, "hr@x.com"));

        verify(documentRepository, never()).save(any());
    }

    // Test 2 — upload valid file saves correctly
    @Test
    void uploadDocument_validFile_savesCorrectly() {
        employeeExists();
        byte[] content = "contract content".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", content);
        when(documentRepository.save(any(EmployeeDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeDocument result = documentService.uploadDocument(
                employeeId, file, DocumentType.CONTRACT, LocalDate.now().plusYears(1), "hr@x.com");

        assertThat(result.getFileData()).isEqualTo(content);
        assertThat(result.getDocumentType()).isEqualTo(DocumentType.CONTRACT);
        assertThat(result.getEmployeeId()).isEqualTo(employeeId);
    }

    // Test 3 — getExpiring returns docs within N days
    @Test
    void getExpiringDocuments_returnsDocsWithinNDays() {
        List<EmployeeDocumentSummary> expected = List.of();
        when(documentRepository.findByExpiryDateBetweenOrderByExpiryDateAsc(
                any(LocalDate.class), any(LocalDate.class))).thenReturn(expected);

        List<EmployeeDocumentSummary> result = documentService.getExpiringDocuments(30);

        assertThat(result).isSameAs(expected);
        verify(documentRepository).findByExpiryDateBetweenOrderByExpiryDateAsc(
                any(LocalDate.class), any(LocalDate.class));
    }

    // Test 4 — delete removes document
    @Test
    void deleteDocument_removesDocument() {
        UUID docId = UUID.randomUUID();
        EmployeeDocument doc = EmployeeDocument.builder()
                .fileData(new byte[0])
                .build();
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        documentService.deleteDocument(docId);

        verify(documentRepository).delete(doc);
    }

    // Test 5 — download returns correct bytes
    @Test
    void downloadDocument_returnsCorrectBytes() {
        UUID docId = UUID.randomUUID();
        byte[] content = "secret payload".getBytes(StandardCharsets.UTF_8);
        EmployeeDocument doc = EmployeeDocument.builder()
                .fileData(content)
                .build();
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        EmployeeDocument result = documentService.downloadDocument(docId);

        assertThat(result.getFileData()).isEqualTo(content);
    }
}
