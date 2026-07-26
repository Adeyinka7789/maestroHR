package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.document.DocumentService;
import com.admtechhub.maestrohr.document.DocumentType;
import com.admtechhub.maestrohr.document.EmployeeDocumentSummary;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ComplianceDashboardService} bucketing — probation urgency and document
 * expiry — plus the DOCUMENT_VAULT gate that keeps the document section (and its query) inert
 * when the tenant's plan/flag excludes the feature.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceDashboardServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock DocumentService documentService;
    @Mock FeatureAccessService featureAccessService;
    @InjectMocks ComplianceDashboardService service;

    private Employee emp(String first, String last, LocalDate probationEnd) {
        Employee e = Employee.builder()
                .firstName(first).lastName(last).jobTitle("Analyst")
                .status(EmployeeStatus.ACTIVE).probationEndDate(probationEnd).build();
        e.setId(UUID.randomUUID());
        return e;
    }

    @Test
    void build_bucketsProbationByUrgency() {
        LocalDate today = LocalDate.now();
        Employee overdue = emp("A", "One", today.minusDays(3));
        Employee soon = emp("B", "Two", today.plusDays(5));
        Employee later = emp("C", "Three", today.plusDays(20));
        when(employeeRepository.findProbationDueThrough(any(), any()))
                .thenReturn(List.of(overdue, soon, later));
        when(featureAccessService.isAvailable(SubscriptionFeature.DOCUMENT_VAULT)).thenReturn(false);

        ComplianceDashboardView v = service.build();

        assertThat(v.probationOverdueCount()).isEqualTo(1);
        assertThat(v.probationDueSoonCount()).isEqualTo(1);
        assertThat(v.probationDueLaterCount()).isEqualTo(1);
        assertThat(v.probationTotal()).isEqualTo(3);
        assertThat(v.probationRows().get(0).bucketKind()).isEqualTo("error"); // overdue, soonest first
        assertThat(v.probationRows().get(0).daysRemaining()).isNegative();
    }

    @Test
    void build_documentSectionInertWhenFeatureOff() {
        when(employeeRepository.findProbationDueThrough(any(), any())).thenReturn(List.of());
        when(featureAccessService.isAvailable(SubscriptionFeature.DOCUMENT_VAULT)).thenReturn(false);

        ComplianceDashboardView v = service.build();

        assertThat(v.documentsAvailable()).isFalse();
        assertThat(v.documentRows()).isEmpty();
        assertThat(v.hasDocuments()).isFalse();
        verifyNoInteractions(documentService); // never queried when the feature is off
    }

    @Test
    void build_bucketsDocumentsByExpiry() {
        LocalDate today = LocalDate.now();
        when(employeeRepository.findProbationDueThrough(any(), any())).thenReturn(List.of());
        when(featureAccessService.isAvailable(SubscriptionFeature.DOCUMENT_VAULT)).thenReturn(true);

        UUID empId = UUID.randomUUID();
        // Build the mocks first — nesting when() inside thenReturn(...) trips Mockito's stubbing.
        EmployeeDocumentSummary expired = docSummary(empId, DocumentType.CONTRACT, "old.pdf", today.minusDays(5));
        EmployeeDocumentSummary soon = docSummary(empId, DocumentType.WORK_PERMIT, "cerpac.pdf", today.plusDays(10));
        EmployeeDocumentSummary later = docSummary(empId, DocumentType.PASSPORT, "passport.pdf", today.plusDays(60));
        when(documentService.getExpiringThrough(any())).thenReturn(List.of(expired, soon, later));

        Employee owner = emp("D", "Four", null);
        owner.setId(empId);
        when(employeeRepository.findAllById(any())).thenReturn(List.of(owner));

        ComplianceDashboardView v = service.build();

        assertThat(v.documentsAvailable()).isTrue();
        assertThat(v.docsExpiredCount()).isEqualTo(1);
        assertThat(v.docsExpiring30Count()).isEqualTo(1);
        assertThat(v.docsExpiring90Count()).isEqualTo(1);
        assertThat(v.documentRows()).hasSize(3);
        assertThat(v.documentRows().get(0).employeeName()).isEqualTo("D Four");
        assertThat(v.documentRows().get(0).documentType()).isEqualTo("Contract");
        assertThat(v.documentRows().get(0).bucketKind()).isEqualTo("error");
    }

    private EmployeeDocumentSummary docSummary(UUID empId, DocumentType type, String name, LocalDate expiry) {
        EmployeeDocumentSummary s = mock(EmployeeDocumentSummary.class);
        when(s.getEmployeeId()).thenReturn(empId);
        when(s.getDocumentType()).thenReturn(type);
        when(s.getFileName()).thenReturn(name);
        when(s.getExpiryDate()).thenReturn(expiry);
        return s;
    }
}
