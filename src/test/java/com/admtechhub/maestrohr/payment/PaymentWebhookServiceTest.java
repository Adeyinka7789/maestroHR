package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payment.dto.PaystackWebhookPayload;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import com.admtechhub.maestrohr.payroll.TransferStatus;
import com.admtechhub.maestrohr.platform.WebhookTenantResolver;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentWebhookServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private PricingService pricingService;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private WebhookTenantResolver webhookTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private PayrollEntryRepository payrollEntryRepository;
    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks private PaymentWebhookService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String REF = "TRF_abc123";

    @BeforeEach
    void stubEntityManagerSession() {
        Query q = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(null);
    }

    private PaystackWebhookPayload payloadWithRef(String reference) {
        PaystackWebhookPayload payload = new PaystackWebhookPayload();
        PaystackWebhookPayload.Data data = new PaystackWebhookPayload.Data();
        data.setReference(reference);
        payload.setData(data);
        return payload;
    }

    private PayrollEntry entryInRun(PayrollRun run, TransferStatus status) {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getCompanyName()).thenReturn("Acme Ltd");

        Employee employee = mock(Employee.class);
        when(employee.getEmployeeNumber()).thenReturn("EMP-001");
        when(employee.getFullName()).thenReturn("John Doe");

        PayrollEntry entry = mock(PayrollEntry.class);
        when(entry.getId()).thenReturn(UUID.randomUUID());
        when(entry.getTransferStatus()).thenReturn(status);
        when(entry.getPayrollRun()).thenReturn(run);
        when(entry.getEmployee()).thenReturn(employee);
        return entry;
    }

    @Test
    void handleTransferSuccess_updatesEntryToPaid() {
        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.canComplete()).thenReturn(false);
        when(run.getTenant()).thenReturn(mock(Tenant.class));
        when(run.getPeriod()).thenReturn("2025-01");
        when(run.getTenant().getCompanyName()).thenReturn("Acme Ltd");

        PayrollEntry entry = entryInRun(run, TransferStatus.PENDING);

        when(webhookTenantResolver.findTenantIdByTransferReference(REF)).thenReturn(Optional.of(TENANT_ID));
        when(payrollEntryRepository.findByTransferReference(REF)).thenReturn(Optional.of(entry));
        when(payrollEntryRepository.countByTransferStatus(any(UUID.class), eq(TransferStatus.PENDING))).thenReturn(1L);

        service.handleTransferSuccess(payloadWithRef(REF));

        verify(entry).setTransferStatus(TransferStatus.PAID);
        verify(payrollEntryRepository).save(entry);
    }

    @Test
    void handleTransferSuccess_allEntriesPaid_completesRun() {
        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.canComplete()).thenReturn(true);
        when(run.getTenant()).thenReturn(mock(Tenant.class));
        when(run.getPeriod()).thenReturn("2025-01");
        when(run.getTenant().getCompanyName()).thenReturn("Acme Ltd");

        PayrollEntry entry = entryInRun(run, TransferStatus.PENDING);

        when(webhookTenantResolver.findTenantIdByTransferReference(REF)).thenReturn(Optional.of(TENANT_ID));
        when(payrollEntryRepository.findByTransferReference(REF)).thenReturn(Optional.of(entry));
        when(payrollEntryRepository.countByTransferStatus(any(UUID.class), eq(TransferStatus.PENDING))).thenReturn(0L);

        service.handleTransferSuccess(payloadWithRef(REF));

        verify(run).setStatus(PayrollStatus.COMPLETED);
        verify(payrollRunRepository).save(run);
    }

    @Test
    void handleTransferSuccess_unknownReference_logsAndIgnores() {
        when(webhookTenantResolver.findTenantIdByTransferReference(REF)).thenReturn(Optional.empty());

        service.handleTransferSuccess(payloadWithRef(REF));

        verify(payrollEntryRepository, never()).findByTransferReference(any());
        verify(payrollEntryRepository, never()).save(any());
    }

    @Test
    void handleTransferFailed_updatesEntryToFailed() {
        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getPeriod()).thenReturn("2025-01");

        PayrollEntry entry = entryInRun(run, TransferStatus.PENDING);

        when(webhookTenantResolver.findTenantIdByTransferReference(REF)).thenReturn(Optional.of(TENANT_ID));
        when(payrollEntryRepository.findByTransferReference(REF)).thenReturn(Optional.of(entry));
        when(webhookTenantResolver.findHrAdminEmails(TENANT_ID)).thenReturn(List.of());

        service.handleTransferFailed(payloadWithRef(REF));

        verify(entry).setTransferStatus(TransferStatus.FAILED);
        verify(payrollEntryRepository).save(entry);
    }

    @Test
    void handleTransferFailed_notifiesHR() {
        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getPeriod()).thenReturn("2025-02");

        PayrollEntry entry = entryInRun(run, TransferStatus.PENDING);

        List<String> hrEmails = List.of("hr@acme.com", "admin@acme.com");
        when(webhookTenantResolver.findTenantIdByTransferReference(REF)).thenReturn(Optional.of(TENANT_ID));
        when(payrollEntryRepository.findByTransferReference(REF)).thenReturn(Optional.of(entry));
        when(webhookTenantResolver.findHrAdminEmails(TENANT_ID)).thenReturn(hrEmails);

        service.handleTransferFailed(payloadWithRef(REF));

        verify(notificationService, times(2)).createInAppNotification(
                anyString(), eq("TRANSFER_FAILED"), anyString(), anyString(), anyString());
    }

    @Test
    void handleTransferSuccess_partialPayment_runStaysDisbursing() {
        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.canComplete()).thenReturn(true);
        when(run.getTenant()).thenReturn(mock(Tenant.class));
        when(run.getPeriod()).thenReturn("2025-01");
        when(run.getTenant().getCompanyName()).thenReturn("Acme Ltd");

        PayrollEntry entry = entryInRun(run, TransferStatus.PENDING);

        when(webhookTenantResolver.findTenantIdByTransferReference(REF)).thenReturn(Optional.of(TENANT_ID));
        when(payrollEntryRepository.findByTransferReference(REF)).thenReturn(Optional.of(entry));
        when(payrollEntryRepository.countByTransferStatus(any(UUID.class), eq(TransferStatus.PENDING))).thenReturn(3L);

        service.handleTransferSuccess(payloadWithRef(REF));

        verify(run, never()).setStatus(any());
        verify(payrollRunRepository, never()).save(run);
    }
}
