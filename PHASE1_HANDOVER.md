# Phase 1 Tenant Isolation Hardening — Handover

## Status: IN PROGRESS (Tasks 1–7 complete, Tasks 8–9 remain)

---

## Tasks Completed

### Task 1 — Delete TenantFilter ✅
**File deleted:**
- `src/main/java/com/admtechhub/maestrohr/auth/TenantFilter.java`

**Why:** Was reading tenant from an unauthenticated `X-Tenant-ID` HTTP header, allowing any caller to spoof tenant identity. Replaced entirely by JWT-sourced TenantContext in JwtAuthFilter.

---

### Task 2 — Harden JwtAuthFilter ✅
**File modified:**
- `src/main/java/com/admtechhub/maestrohr/auth/JwtAuthFilter.java`

**Changes:**
- After extracting `tenantId` from JWT claims, validates it is non-null and non-blank → returns HTTP 401 with JSON body if missing.
- Validates `tenantId` is a well-formed UUID via `UUID.fromString()` → returns HTTP 401 if malformed.
- Added private `writeUnauthorized(response, message)` helper.
- `TenantContext.clear()` remains in `finally` block — unchanged, already correct.

---

### Task 3 — Add TenantValidationFilter + wire into SecurityConfig ✅
**Files created/modified:**
- `src/main/java/com/admtechhub/maestrohr/auth/TenantValidationFilter.java` ← NEW
- `src/main/java/com/admtechhub/maestrohr/config/SecurityConfig.java`

**Changes:**
- `TenantValidationFilter` is a `OncePerRequestFilter` that runs after `JwtAuthFilter` in the Spring Security chain.
- For any non-public path: if `TenantContext.getCurrentTenant()` is null, returns HTTP 403 with JSON body.
- Not annotated `@Component` — instantiated as `new TenantValidationFilter()` in `SecurityConfig` to prevent double-registration as a Servlet filter.
- `SecurityConfig` addition: `.addFilterAfter(new TenantValidationFilter(), JwtAuthFilter.class)`
- Public paths exempted: `/api/auth/**`, `/actuator/health`, `/actuator/info`, `/api/pricing/public`, `/login`, `/register`, `/`, `/css/**`, `/js/**`, `/images/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/favicon**`

---

### Task 4 — Fix SQL injection in DataSourceConfig ✅
**File modified:**
- `src/main/java/com/admtechhub/maestrohr/config/DataSourceConfig.java`

**Changes:**
- Replaced string concatenation (`"... '" + tenant + "'"`) with `PreparedStatement` (`ps.setString(1, value)`).
- Now **always** writes `app.current_tenant` — even when no tenant context (writes empty string `''`). This clears stale tenant IDs on pooled connections reused across requests.
- `is_local` parameter changed to `false` (session-level) so the value persists for the duration of the connection checkout.
- Empty string is safe: RLS policies and `@SQLRestriction` use `NULLIF(..., '')::uuid` which converts `''` to NULL, making `tenant_id = NULL` evaluate to false → no rows leak.

---

### Task 5 — Delete HibernateRLSInterceptor ✅
**File deleted:**
- `src/main/java/com/admtechhub/maestrohr/config/HibernateRLSInterceptor.java`

**Why:** Implemented `StatementInspector` but `inspect()` was a pass-through (returned `sql` unchanged). Was never registered with Hibernate (`hibernate.session_factory.statement_inspector` never set in `application.yml`). Dead code.

---

### Task 6 — @SQLRestriction on all 22 tenant-scoped entities ✅
**Expression used on every entity:**
```
tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
```
`NULLIF` converts empty string (written by DataSourceConfig when no tenant) to NULL, making the comparison safely return no rows rather than throwing a cast error.

**Files modified (all had import `org.hibernate.annotations.SQLRestriction` added + annotation added above class declaration):**

| Entity | File |
|---|---|
| Employee | `employee/Employee.java` — was commented out, now uncommented + expression updated |
| Department | `employee/Department.java` |
| PayGrade | `employee/PayGrade.java` |
| PayrollRun | `payroll/PayrollRun.java` |
| PayrollEntry | `payroll/PayrollEntry.java` |
| LeaveRequest | `leave/LeaveRequest.java` |
| LeaveBalance | `leave/LeaveBalance.java` |
| LeaveType | `leave/LeaveType.java` |
| AttendanceRecord | `attendance/AttendanceRecord.java` |
| ExitRequest | `exit/ExitRequest.java` |
| EmployeeClearance | `exit/EmployeeClearance.java` |
| ClearanceItem | `exit/ClearanceItem.java` |
| FinalSettlement | `exit/FinalSettlement.java` |
| JobPosting | `recruitment/JobPosting.java` |
| JobApplication | `recruitment/JobApplication.java` |
| TrainingProgram | `training/TrainingProgram.java` |
| EmployeeTraining | `training/EmployeeTraining.java` |
| Certification | `training/Certification.java` |
| ReviewCycle | `performance/ReviewCycle.java` |
| ReviewTemplate | `performance/ReviewTemplate.java` |
| AuditTrail | `audit/AuditTrail.java` |
| InAppNotification | `notification/InAppNotification.java` |

**Entities deliberately skipped:**
- `User` — read before TenantContext is set during login; adding restriction would break authentication
- `Tenant` — `id` is the isolation key, not `tenant_id`; already has PostgreSQL RLS from V2
- `ReviewSection`, `ReviewQuestion` — no `tenant_id` column; scoped via FK chain through ReviewTemplate
- `PricingConfig` — global admin config, not tenant-scoped
- `SalaryPayment` — plain POJO, not a JPA `@Entity`

---

### Task 7 — Fix cross-tenant JPQL queries ✅
**Files modified:**
- `src/main/java/com/admtechhub/maestrohr/payroll/PayrollRunRepository.java`
- `src/main/java/com/admtechhub/maestrohr/payroll/PayrollRunService.java`

**Changes:**
1. **Removed** `findPendingApprovals()` from `PayrollRunRepository` — was an untenanted `@Query` with zero callers (dead code). Callers should use `findByTenant_IdAndStatus(tenantId, PENDING_APPROVAL)` which already existed.
2. **Removed** `existsByPayrollMonthAndPayrollYear(month, year)` — untenanted derived query with zero callers. Tenant-scoped version `existsByTenant_IdAndPayrollMonthAndPayrollYear` already existed.
3. **Replaced** untenanted `findByYear(year)` with `findByTenantIdAndYear(tenantId, year)` — adds explicit `p.tenant.id = :tenantId` clause to the JPQL. (Zero callers found; rename is safe.)
4. **Removed redundant in-memory filter** from `PayrollRunService.computePayroll()` — was manually filtering `employeeRepository.findByStatus(ACTIVE)` by tenant. Now that `@SQLRestriction` is active on `Employee`, the DB query returns only the current tenant's employees. The `.stream().filter(...)` was dead weight.

**LeaveRequestRepository.findByStatus(Pageable)** — no change needed. It is a derived query; `@SQLRestriction` on `LeaveRequest` automatically scopes it at the SQL level. Same for `DashboardApiController`'s call to `findByStatus(PENDING)`.

---

## Tasks Remaining

### Task 8 — Flyway migration V20: enable PostgreSQL RLS on all tenant-scoped tables ❌ NOT STARTED

**File to create:**
`src/main/resources/db/migration/V20__enable_rls_all_tables.sql`

**Requirements:**
- Enable `ROW LEVEL SECURITY` (without `FORCE`) on all 22 tenant-scoped tables.
- Do NOT use `FORCE ROW LEVEL SECURITY` — Flyway migrations run as the table owner and would be blocked by their own policies.
- Do NOT touch `users` table — accessed before TenantContext is set during login.
- Do NOT touch `tenants` table — already has RLS from V2 (leave V2 as-is).
- Policy expression must use `NULLIF` to handle empty string from DataSourceConfig:
  ```sql
  tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
  ```
- Use a single combined policy per table covering SELECT/INSERT/UPDATE/DELETE (no `FOR` clause).

**Tables to cover (22):**
```
employees, departments, pay_grades,
payroll_runs, payroll_entries,
leave_requests, leave_balances, leave_types,
attendance_records,
exit_requests, employee_clearance, clearance_items, final_settlements,
job_postings, job_applications,
training_programs, employee_trainings, certifications,
review_cycles, review_templates,
audit_trail, in_app_notifications
```

---

### Task 9 — Write 3 integration tests for tenant isolation ❌ NOT STARTED

**File to create:**
`src/test/java/com/admtechhub/maestrohr/TenantIsolationTest.java`

**Use:** `@WebMvcTest(EmployeeController.class)` with `@MockBean JwtService` and `@MockBean EmployeeService`

**Test 1 — Valid tenant JWT returns 200:**
- Mock `jwtService.isTokenValid("token-a")` → true
- Mock `jwtService.extractTenantId("token-a")` → `TENANT_A.toString()` (valid UUID)
- Mock `jwtService.extractRole(...)` → `"HR_ADMIN"`
- Mock `employeeService.getAllEmployees(any())` → `Page.empty()`
- `GET /api/employees` with `Authorization: Bearer token-a` → expect `200 OK`

**Test 2 — No JWT on protected endpoint returns 403:**
- No Authorization header
- JwtAuthFilter passes through (null token), TenantContext remains null
- TenantValidationFilter catches → `403 Forbidden`
- `GET /api/employees` with no header → expect `403`

**Test 3 — Cross-tenant employee lookup returns 404:**
- Same valid JWT setup as Test 1
- Mock `employeeService.getEmployee(tenantBEmployeeId)` → `Optional.empty()` (simulates `@SQLRestriction` hiding the row)
- `GET /api/employees/{tenantBEmployeeId}` with tenant A's JWT → expect `404`

**Note:** The method name for single-employee lookup (`getEmployee`, `findById`, etc.) should be verified against `EmployeeController` before writing. The controller was partially read — only first 60 lines were seen.

---

## Next Step to Resume From

**Start with Task 8.** Open and write:
```
src/main/resources/db/migration/V20__enable_rls_all_tables.sql
```

The SQL template for each table is:
```sql
ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON <table>
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

Repeat for all 22 tables listed above, then proceed to Task 9 (tests).
