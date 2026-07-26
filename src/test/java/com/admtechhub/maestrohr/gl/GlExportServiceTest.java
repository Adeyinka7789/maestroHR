package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.gl.GlDtos.JournalView;
import com.admtechhub.maestrohr.gl.GlExportService.ExportFormat;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlExportServiceTest {

    @Mock GlAccountConfigRepository configRepository;
    @Mock PayrollRunRepository payrollRunRepository;
    @Mock PayrollEntryRepository payrollEntryRepository;

    @InjectMocks GlExportService service;

    static final UUID RUN = UUID.randomUUID();

    @BeforeEach void bind() { TenantContext.setCurrentTenant(UUID.randomUUID().toString()); }
    @AfterEach void clear() { TenantContext.clear(); }

    private PayrollEntry entry(Employee emp, long net, long paye, long pensionEmp,
                               long pensionEmployer, long nhf) {
        return PayrollEntry.builder()
                .employee(emp)
                .netSalary(net).payeTax(paye)
                .pensionEmployee(pensionEmp).pensionEmployer(pensionEmployer)
                .nhfDeduction(nhf)
                .build();
    }

    private Employee employee(CostCenter cc) {
        Employee e = Employee.builder().firstName("A").lastName("B").costCenter(cc).build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private void stubRun() {
        PayrollRun run = PayrollRun.builder()
                .payrollMonth(7).payrollYear(2026).status(PayrollStatus.APPROVED).build();
        run.setId(RUN);
        when(payrollRunRepository.findById(RUN)).thenReturn(Optional.of(run));
        when(configRepository.findFirstBy()).thenReturn(Optional.empty()); // default account codes
    }

    @Test
    void buildJournal_isBalanced_andGroupsExpensesByCostCenter() {
        CostCenter lekki = CostCenter.builder().name("Lekki Outlet").code("LEKKI").glAccountCode("6001").build();
        lekki.setId(UUID.randomUUID());

        // Lekki: salaryExpense = 80000+15000+5000+0 = 100,000; employer pension 6,000.
        // Unassigned: salaryExpense = 40000+8000+2500+0 = 50,500; employer pension 3,000.
        stubRun();
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(RUN)).thenReturn(List.of(
                entry(employee(lekki), 80_000, 15_000, 5_000, 6_000, 0),
                entry(employee(null), 40_000, 8_000, 2_500, 3_000, 0)));

        JournalView j = service.buildJournal(RUN);

        assertThat(j.balanced()).isTrue();
        assertThat(j.periodLabel()).isEqualTo("July 2026");

        // Debits: 100000+50500 salary + 6000+3000 employer pension = 159,500.
        long debit = j.lines().stream().mapToLong(JournalView.JournalLine::debitKobo).sum();
        long credit = j.lines().stream().mapToLong(JournalView.JournalLine::creditKobo).sum();
        assertThat(debit).isEqualTo(159_500);
        assertThat(credit).isEqualTo(159_500);

        // Two salary-expense lines, one per cost center; Lekki uses its own GL account override.
        assertThat(j.lines()).anyMatch(l -> l.debitKobo() == 100_000
                && l.costCenter().equals("Lekki Outlet") && l.accountCode().equals("6001"));
        assertThat(j.lines()).anyMatch(l -> l.debitKobo() == 50_500
                && l.costCenter().equals("Unassigned") && l.accountCode().equals("6000"));
        // Consolidated pension-payable credit = 5000+6000+2500+3000 = 16,500.
        assertThat(j.lines()).anyMatch(l -> l.creditKobo() == 16_500 && l.accountName().contains("Pension Payable"));
        // NHF and Other are zero → no lines for them.
        assertThat(j.lines()).noneMatch(l -> l.accountName().contains("NHF"));
    }

    @Test
    void exportCsv_generic_hasHeaderAndBalances() {
        stubRun();
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(RUN)).thenReturn(List.of(
                entry(employee(null), 40_000, 8_000, 2_500, 3_000, 0)));

        String csv = new String(service.exportCsv(RUN, ExportFormat.GENERIC), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("Account Code,Account Name,Cost Center,Debit,Credit,Memo");
        assertThat(csv).contains("Salary & Wages Expense");
        assertThat(csv).contains("Net Pay / Bank");
    }

    @Test
    void exportCsv_quickbooks_hasQbHeader() {
        stubRun();
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(RUN)).thenReturn(List.of(
                entry(employee(null), 40_000, 8_000, 2_500, 3_000, 0)));

        String csv = new String(service.exportCsv(RUN, ExportFormat.QUICKBOOKS), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("Date,Journal No,Account,Debits,Credits,Description,Name,Class");
        assertThat(csv).contains("PAY-202607");
    }
}
