package com.admtechhub.maestrohr.disbursement;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.ValidationResult;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackApiException;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackBank;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayoutValidationServiceTest {

    @Mock PaystackClient paystackClient;
    @Mock PayrollEntryRepository payrollEntryRepository;

    @InjectMocks PayoutValidationService service;

    static final UUID RUN = UUID.randomUUID();

    @BeforeEach void bind() { TenantContext.setCurrentTenant(UUID.randomUUID().toString()); }
    @AfterEach void clear() { TenantContext.clear(); }

    private PaystackBank bank(String name, String code) {
        PaystackBank b = new PaystackBank();
        b.setName(name);
        b.setCode(code);
        return b;
    }

    private Employee emp(String first, String last, String bankName, String acct, String acctName) {
        Employee e = Employee.builder()
                .firstName(first).lastName(last).employeeNumber("EMP-1")
                .bankName(bankName).bankAccountNumber(acct).bankAccountName(acctName).build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private PayrollEntry entry(Employee e) {
        return PayrollEntry.builder().employee(e).netSalary(100_000L).build();
    }

    private PaystackResponse.ResolveAccountData resolved(String name) {
        PaystackResponse.ResolveAccountData d = mock(PaystackResponse.ResolveAccountData.class);
        lenient().when(d.getAccountName()).thenReturn(name);
        return d;
    }

    @Test
    void validate_classifiesOkMismatchMissingAndUnknownBank() {
        Employee okEmp = emp("Okeke", "Michael", "GTBank", "0123456789", "Okeke Michael");
        Employee mismatch = emp("Okeke", "Michael", "GTBank", "0000000001", "Okeke Michael");
        Employee missing = emp("No", "Bank", "", "", "");
        Employee unknownBank = emp("Weird", "Bank", "Nonexistent MFB", "0000000002", "Weird Bank");

        when(payrollEntryRepository.findByPayrollRunId(eq(RUN), any()))
                .thenReturn(List.of(entry(okEmp), entry(mismatch), entry(missing), entry(unknownBank)));
        when(paystackClient.getBanks()).thenReturn(List.of(bank("GTBank", "058")));
        // Build the mocks first — nesting when() inside thenReturn(...) trips Mockito's stubbing.
        PaystackResponse.ResolveAccountData okData = resolved("Okeke Michael");
        PaystackResponse.ResolveAccountData mismatchData = resolved("Okeke Michael Chukwu");
        // Exact name → OK; extra token → WARN.
        when(paystackClient.resolveAccount("0123456789", "058")).thenReturn(okData);
        when(paystackClient.resolveAccount("0000000001", "058")).thenReturn(mismatchData);

        ValidationResult r = service.validateRun(RUN);

        assertThat(r.okCount()).isEqualTo(1);
        assertThat(r.warnCount()).isEqualTo(1);
        assertThat(r.errorCount()).isEqualTo(2); // missing details + unrecognized bank
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.rows()).anyMatch(row -> row.status().equals("WARN") && row.message().contains("mismatch"));
        assertThat(r.rows()).anyMatch(row -> row.status().equals("ERROR") && row.message().contains("Missing"));
        assertThat(r.rows()).anyMatch(row -> row.status().equals("ERROR") && row.message().contains("Unrecognized"));
    }

    @Test
    void validate_paystackErrorIsBlockingError() {
        Employee e = emp("Ada", "Obi", "GTBank", "0123456789", "Ada Obi");
        when(payrollEntryRepository.findByPayrollRunId(eq(RUN), any())).thenReturn(List.of(entry(e)));
        when(paystackClient.getBanks()).thenReturn(List.of(bank("GTBank", "058")));
        when(paystackClient.resolveAccount("0123456789", "058"))
                .thenThrow(new PaystackApiException("gateway down"));

        ValidationResult r = service.validateRun(RUN);

        assertThat(r.errorCount()).isEqualTo(1);
        assertThat(r.rows().get(0).message()).contains("Could not verify");
    }

    @Test
    void structuralBlockerCount_countsMissingBankDetailsOnly() {
        Employee good = emp("Ada", "Obi", "GTBank", "0123456789", "Ada Obi");
        Employee bad = emp("No", "Acct", "GTBank", "", "No Acct");
        when(payrollEntryRepository.findByPayrollRunId(eq(RUN), any()))
                .thenReturn(List.of(entry(good), entry(bad)));

        assertThat(service.structuralBlockerCount(RUN)).isEqualTo(1);
    }
}
