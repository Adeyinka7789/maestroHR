package com.admtechhub.maestrohr.disbursement;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.ValidationResult;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.ValidationRow;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackBank;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackException;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Pre-run NUBAN validation: before money moves, resolve every employee's account with Paystack's
 * name-enquiry ({@link PaystackClient#resolveAccount}) and flag problems the finance admin should
 * fix or acknowledge first. ERROR = can't pay (missing/unrecognized bank, unresolvable account);
 * WARN = the resolved account name doesn't match what's on file (possible wrong account);
 * OK = verified match. Employee bank is stored as a name, so it is mapped to a Paystack bank code
 * via {@link PaystackClient#getBanks()} first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutValidationService {

    private final PaystackClient paystackClient;
    private final PayrollEntryRepository payrollEntryRepository;

    @Transactional(readOnly = true)
    public ValidationResult validateRun(UUID runId) {
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(runId, currentTenantId());
        Map<String, String> bankCodes = bankNameToCode();

        List<ValidationRow> rows = new ArrayList<>(entries.size());
        int ok = 0, warn = 0, err = 0;

        for (PayrollEntry e : entries) {
            Employee emp = e.getEmployee();
            String acct = emp.getBankAccountNumber();
            String bankName = emp.getBankName();
            String onFile = emp.getBankAccountName();

            if (isBlank(acct) || isBlank(bankName)) {
                rows.add(row(emp, bankName, acct, "—", "ERROR", "Missing bank details"));
                err++;
                continue;
            }
            String code = resolveBankCode(bankName, bankCodes);
            if (code == null) {
                rows.add(row(emp, bankName, acct, "—", "ERROR",
                        bankCodes.isEmpty() ? "Bank directory unavailable — try again" : "Unrecognized bank"));
                err++;
                continue;
            }
            try {
                PaystackResponse.ResolveAccountData data = paystackClient.resolveAccount(acct, code);
                String resolved = data != null ? data.getAccountName() : null;
                if (isBlank(resolved)) {
                    rows.add(row(emp, bankName, acct, "—", "ERROR", "Account could not be resolved"));
                    err++;
                } else if (namesMatch(resolved, onFile)) {
                    rows.add(row(emp, bankName, acct, resolved, "OK", ""));
                    ok++;
                } else {
                    rows.add(row(emp, bankName, acct, resolved, "WARN",
                            "Name mismatch: \"" + resolved + "\" vs \"" + (onFile == null ? "" : onFile) + "\""));
                    warn++;
                }
            } catch (PaystackException ex) {
                log.warn("Account resolve failed for employee {}: {}", emp.getEmployeeNumber(), ex.getMessage());
                rows.add(row(emp, bankName, acct, "—", "ERROR", "Could not verify with Paystack"));
                err++;
            }
        }
        return new ValidationResult(ok, warn, err, rows);
    }

    /**
     * Structural blockers computed WITHOUT calling Paystack — entries that simply cannot be paid
     * (no account number or no bank on file). Used to hard-gate the Disburse action so it never
     * depends on a live name-enquiry call being reachable.
     */
    @Transactional(readOnly = true)
    public int structuralBlockerCount(UUID runId) {
        int blockers = 0;
        for (PayrollEntry e : payrollEntryRepository.findByPayrollRunId(runId, currentTenantId())) {
            Employee emp = e.getEmployee();
            if (isBlank(emp.getBankAccountNumber()) || isBlank(emp.getBankName())) {
                blockers++;
            }
        }
        return blockers;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ValidationRow row(Employee emp, String bank, String acct, String resolved, String status, String msg) {
        return new ValidationRow(emp.getId(), emp.getFullName(), emp.getEmployeeNumber(),
                bank == null ? "—" : bank, acct == null ? "—" : acct, resolved, status, msg);
    }

    /** Lowercased, punctuation-stripped Paystack bank name → code. Empty if the directory is down. */
    private Map<String, String> bankNameToCode() {
        Map<String, String> map = new HashMap<>();
        try {
            for (PaystackBank b : paystackClient.getBanks()) {
                if (b.getName() != null && b.getCode() != null) {
                    map.put(normalize(b.getName()), b.getCode());
                }
            }
        } catch (Exception e) {
            log.warn("Could not load Paystack bank directory: {}", e.getMessage());
        }
        return map;
    }

    /** Exact normalized match, else a best-effort contains match (e.g. "GTBank" ↔ "Guaranty Trust"). */
    private String resolveBankCode(String bankName, Map<String, String> bankCodes) {
        if (bankCodes.isEmpty()) {
            return null;
        }
        String key = normalize(bankName);
        String exact = bankCodes.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> en : bankCodes.entrySet()) {
            if (en.getKey().contains(key) || key.contains(en.getKey())) {
                return en.getValue();
            }
        }
        return null;
    }

    /** Order-independent token-set equality; any extra/missing token is treated as a mismatch. */
    private boolean namesMatch(String a, String b) {
        return tokens(a).equals(tokens(b));
    }

    private Set<String> tokens(String s) {
        Set<String> set = new TreeSet<>();
        if (s != null) {
            for (String t : s.toUpperCase(Locale.ENGLISH).split("[^A-Z0-9]+")) {
                if (!t.isBlank()) {
                    set.add(t);
                }
            }
        }
        return set;
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
