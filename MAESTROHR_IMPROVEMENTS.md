# MaestroHR — Claude Code Improvement Guide

> Drop this file in your project root alongside `pom.xml`.
> Work through each phase in order. Do NOT skip ahead.

---

## HOW TO USE THIS GUIDE

1. Open Claude Code in your project root: `claude`
2. Paste the **"Start of Phase"** prompt exactly as written
3. Claude Code will read files, plan, and wait for your go-ahead
4. Review its plan, then say **"Proceed"**
5. When a phase is complete, mark it done and move to the next

**Rule:** One phase at a time. Each phase must pass its verification
checklist before you start the next.

---

## PROGRESS TRACKER

```
[ ] Phase 1 — Tenant isolation hardening     (CRITICAL — do first)
[ ] Phase 2 — Module integration wiring      (payroll, leave, attendance)
[ ] Phase 3 — Recruitment → onboarding flow
[ ] Phase 4 — Exit → final settlement flow
[ ] Phase 5 — Performance → pay grade link
[ ] Phase 6 — Leave accrual engine
[ ] Phase 7 — Subscription feature gating (AOP)
[ ] Phase 8 — Structured logging + Sentry grouping
```

---

---

# PHASE 1 — Tenant Isolation Hardening

**Why this is first:** `@SQLRestriction` is currently commented out on your
entities. This means a single missing `TenantContext` call could expose one
tenant's data to another. This is a production data-leak risk that must be
fixed before anything else ships.

**What this phase does:**
- Re-enables `@SQLRestriction` on all entities
- Adds a `OncePerRequestFilter` that rejects requests with no tenant context
- Writes cross-tenant isolation tests to prove the fix works

---

## Phase 1 — Start Prompt (paste into Claude Code)

```
Read these files carefully before writing a single line of code:

UNDERSTAND THE CURRENT STATE FIRST:
- src/main/java/com/maestrohr/common/entity/BaseEntity.java
- src/main/java/com/maestrohr/common/tenant/TenantContext.java
- src/main/java/com/maestrohr/common/tenant/HibernateRLSInterceptor.java
- src/main/java/com/maestrohr/auth/filter/JwtAuthFilter.java
- src/main/resources/db/migration/V2__rls_setup.sql
- src/main/java/com/maestrohr/employee/entity/Employee.java
- src/main/java/com/maestrohr/payroll/entity/PayrollRun.java
- src/main/java/com/maestrohr/config/SecurityConfig.java

Once you have read all files, do the following analysis and
REPORT BACK TO ME — do not write any code yet:

1. List every entity class that has @SQLRestriction commented out
2. Show me the current TenantContext set/clear lifecycle (where it's set,
   where it's cleared, and any gaps you can see)
3. Show me whether the JWT filter currently validates tenantId from the
   token claims before setting TenantContext
4. Identify any @Repository or @Service methods that use native queries
   or JDBC directly (these bypass @SQLRestriction)

After your analysis, propose the exact changes you will make for my approval.
Do NOT write any code until I say "Proceed".
```

---

## Phase 1 — Expected Plan (what Claude Code should propose)

Before you say "Proceed", verify the plan includes all of these:

- [ ] Re-enable `@SQLRestriction(value = "tenant_id = :tenantId")` on BaseEntity
      or individually on each entity
- [ ] Add a `TenantValidationFilter` that runs before `JwtAuthFilter` and
      throws `403` if `TenantContext.getCurrentTenant()` is null after auth
- [ ] Ensure `TenantContext.clear()` is called in a `finally` block (not just
      on happy path) to prevent ThreadLocal leaks across requests
- [ ] Add `@Param("tenantId")` binding so Hibernate resolves the filter param
- [ ] Write at minimum 3 tests:
      - Authenticated request with valid tenant → gets only their data
      - Authenticated request where tenantId in token ≠ tenantId in URL → 403
      - Raw ID lookup (e.g. GET /employees/{id}) for another tenant's record → 404

If the plan is missing any of the above, tell Claude Code what to add before
proceeding.

---

## Phase 1 — Verification Checklist

Run these before marking Phase 1 complete:

```bash
# All tests must pass
./mvnw test -pl . -Dtest="TenantIsolation*,*SecurityTest"

# No entity should have SQLRestriction commented out
grep -r "SQLRestriction" src/main/java --include="*.java"
# Every result should NOT have // before it

# TenantContext clear must be in finally blocks
grep -A5 "TenantContext.setCurrentTenant" src/main/java --include="*.java" -r
```

**Pass criteria:** All tests green. No commented-out `@SQLRestriction`. Every
`setCurrentTenant` call has a corresponding `clear()` in a `finally` block.

---

---

# PHASE 2 — Payroll Integration (Leave + Attendance)

**Why:** Right now payroll runs in isolation. It does not deduct unpaid leave
days or late/absent penalties. Every payroll run is therefore incorrect for any
employee who had leave or attendance issues in the period.

**What this phase does:**
- Wires `LeaveService` into `PayrollCalculationService` for unpaid day deductions
- Wires `AttendanceService` into `PayrollCalculationService` for absence/late penalties
- Uses Spring Application Events so modules stay decoupled
- All monetary values remain in kobo throughout

---

## Phase 2 — Start Prompt (paste into Claude Code)

```
Read these files carefully before writing any code:

PAYROLL ENGINE:
- src/main/java/com/maestrohr/payroll/service/PayrollCalculationService.java
- src/main/java/com/maestrohr/payroll/entity/PayrollRun.java
- src/main/java/com/maestrohr/payroll/entity/PayslipEntry.java
- src/main/java/com/maestrohr/payroll/dto/PayrollRunRequest.java

LEAVE MODULE:
- src/main/java/com/maestrohr/leave/service/LeaveService.java
- src/main/java/com/maestrohr/leave/entity/LeaveRequest.java
- src/main/java/com/maestrohr/leave/entity/LeaveBalance.java

ATTENDANCE MODULE:
- src/main/java/com/maestrohr/attendance/service/AttendanceService.java
- src/main/java/com/maestrohr/attendance/entity/AttendanceRecord.java

SHARED:
- src/main/java/com/maestrohr/common/entity/BaseEntity.java

Once you have read all files, analyse and REPORT BACK — do not write code yet:

1. Show me the exact method in PayrollCalculationService where per-employee
   gross and net pay is calculated (show the method signature and key lines)
2. Does LeaveService already have a method to get unpaid leave days for an
   employee within a date range? If yes, show it. If no, note that it needs
   creating.
3. Does AttendanceService already have a method to get absent/late days for
   an employee within a date range? If yes, show it. If no, note it.
4. Are monetary deductions stored in kobo everywhere, or is there any naira
   usage I need to know about?

Then propose:
- The integration approach (direct service injection vs Spring Events — 
  recommend what suits the existing pattern)
- The deduction logic: how unpaid leave days translate to a kobo deduction
  (hint: daily rate = monthly gross / working days in month)
- Any new DTOs or methods needed

Do NOT write any code until I say "Proceed".
```

---

## Phase 2 — Expected Plan

Before "Proceed", verify:

- [ ] Daily rate calculation uses `(grossSalaryKobo / workingDaysInMonth)`
      — no floating point; integer division in kobo is fine
- [ ] Unpaid leave deduction and attendance deduction appear as **separate
      line items** on the payslip, not silently subtracted from gross
- [ ] If LeaveService or AttendanceService methods are missing, the plan
      creates them with correct tenant scoping
- [ ] No direct field access across module boundaries — goes through service
      methods only
- [ ] New integration tests: payroll run for employee with 3 unpaid leave days
      must produce correct net pay deduction

---

## Phase 2 — Verification Checklist

```bash
./mvnw test -Dtest="PayrollIntegration*,PayrollCalculation*"

# Payslip entries must include leave and attendance line items
# Search for the deduction line item creation in payslip code
grep -r "UNPAID_LEAVE\|ATTENDANCE_DEDUCTION" src/main/java --include="*.java"
```

**Pass criteria:** Tests green. Payslip entries table has deduction type enum
values for `UNPAID_LEAVE` and `ATTENDANCE_DEDUCTION`. No naira values anywhere
in the new code.

---

---

# PHASE 3 — Recruitment → Employee Onboarding Flow

**Why:** When a candidate accepts an offer, HR currently has to manually create
the employee record. This is error-prone and creates a gap where someone is
"hired" but not yet in the system.

**What this phase does:**
- On offer letter acceptance, automatically creates an `Employee` record with
  status `ONBOARDING`
- Triggers the welcome email + SMS via the existing notification module
- Links the `JobApplication` to the new `Employee` for audit trail

---

## Phase 3 — Start Prompt (paste into Claude Code)

```
Read these files before writing any code:

RECRUITMENT MODULE:
- src/main/java/com/maestrohr/recruitment/entity/JobApplication.java
- src/main/java/com/maestrohr/recruitment/entity/JobPosting.java
- src/main/java/com/maestrohr/recruitment/service/RecruitmentService.java
- src/main/java/com/maestrohr/recruitment/controller/RecruitmentController.java

EMPLOYEE MODULE:
- src/main/java/com/maestrohr/employee/entity/Employee.java
- src/main/java/com/maestrohr/employee/service/EmployeeService.java
- src/main/java/com/maestrohr/employee/dto/CreateEmployeeRequest.java

NOTIFICATION:
- src/main/java/com/maestrohr/notification/service/NotificationService.java

SHARED EVENTS (if exists, otherwise note it):
- src/main/java/com/maestrohr/common/event/

Once you have read all files, report back:

1. What is the current flow when an offer is accepted? Show the exact method
   in RecruitmentService that handles offer acceptance.
2. What fields on Employee are required at creation vs optional (filled later
   during onboarding)?
3. Does an ApplicationEvent infrastructure already exist in this project?
4. What notification templates exist for new employee welcome?

Then propose the full flow using Spring ApplicationEvents:
- OfferAcceptedEvent (published by RecruitmentService)
- EmployeeOnboardingListener (subscribes, creates Employee, fires notification)
- The exact Employee fields populated from JobApplication data

Do NOT write any code until I say "Proceed".
```

---

## Phase 3 — Expected Plan

- [ ] Uses `ApplicationEventPublisher` — no direct import of EmployeeService
      inside RecruitmentService (keeps modules decoupled)
- [ ] New `Employee` is created with status `ONBOARDING`, not `ACTIVE`
      (HR must complete onboarding before status changes)
- [ ] `JobApplication.employeeId` is set after employee creation (audit link)
- [ ] Welcome notification uses existing `NotificationService` — no new
      email/SMS infrastructure
- [ ] Flyway migration if any new column added to `job_applications` table

---

## Phase 3 — Verification Checklist

```bash
./mvnw test -Dtest="RecruitmentOnboarding*,OfferAccepted*"

# Confirm event class exists
find src -name "OfferAcceptedEvent.java"

# Confirm listener exists
find src -name "EmployeeOnboardingListener.java"
```

---

---

# PHASE 4 — Exit → Final Settlement Flow

**Why:** When an employee exits, HR must manually calculate their final pay.
This phase automates the trigger so exit clearance completion fires a final
payroll calculation covering: prorated salary for the month, leave encashment
(unused leave days × daily rate), and any outstanding deductions.

---

## Phase 4 — Start Prompt (paste into Claude Code)

```
Read these files before writing any code:

EXIT MODULE:
- src/main/java/com/maestrohr/exit/entity/ExitRequest.java
- src/main/java/com/maestrohr/exit/entity/ClearanceChecklist.java
- src/main/java/com/maestrohr/exit/service/ExitService.java

PAYROLL MODULE:
- src/main/java/com/maestrohr/payroll/service/PayrollCalculationService.java
- src/main/java/com/maestrohr/payroll/entity/PayrollRun.java

LEAVE MODULE:
- src/main/java/com/maestrohr/leave/service/LeaveService.java
- src/main/java/com/maestrohr/leave/entity/LeaveBalance.java

Once you have read all files, report back:

1. What triggers "clearance complete" in ExitService? Show the method.
2. Is there already a "final settlement" payroll run type, or does FINAL_SETTLEMENT
   need to be added to the PayrollRunType enum?
3. How are unused leave balances currently stored — as days or hours?
4. What is the last working day field on ExitRequest called?

Then propose:
- ExitClearanceCompletedEvent flow
- Final payroll run calculation: prorated salary + leave encashment in kobo
- How the final payslip is flagged differently from regular monthly payslips

Do NOT write any code until I say "Proceed".
```

---

## Phase 4 — Expected Plan

- [ ] `FINAL_SETTLEMENT` added to `PayrollRunType` enum if not present
- [ ] Prorated salary = `(grossSalaryKobo / totalWorkingDaysInMonth) × daysWorked`
- [ ] Leave encashment = `unusedDays × dailyRateKobo` (kobo, integer math)
- [ ] Final payslip PDF generated via existing notification module
- [ ] `Employee.status` set to `EXITED` after final settlement calculation
- [ ] Flyway migration if enum column change needed

---

## Phase 4 — Verification Checklist

```bash
./mvnw test -Dtest="ExitSettlement*,FinalPayroll*"

grep -r "FINAL_SETTLEMENT" src/main/java --include="*.java"
grep -r "ExitClearanceCompletedEvent" src/main/java --include="*.java"
```

---

---

# PHASE 5 — Performance Review → Pay Grade Link

**Why:** After a completed review cycle, nothing currently happens in the system.
HR has to manually check scores and decide on pay grade changes. This phase
adds a flag on completed reviews that surfaces pay grade recommendations to HR
without auto-approving them (HR still decides).

---

## Phase 5 — Start Prompt (paste into Claude Code)

```
Read these files before writing any code:

PERFORMANCE MODULE:
- src/main/java/com/maestrohr/performance/entity/ReviewCycle.java
- src/main/java/com/maestrohr/performance/entity/PerformanceReview.java
- src/main/java/com/maestrohr/performance/service/PerformanceService.java

EMPLOYEE MODULE:
- src/main/java/com/maestrohr/employee/entity/PayGrade.java
- src/main/java/com/maestrohr/employee/service/EmployeeService.java

Once you have read all files, report back:

1. What field represents the final score on PerformanceReview?
2. Is there a ReviewStatus enum? What are the terminal states?
3. What does PayGrade look like — is it an entity with salary bands or
   just a label?
4. Is there a notification channel currently used for HR alerts?

Then propose:
- Score thresholds that should trigger a "pay grade review recommended" flag
  (suggest sensible defaults, I will adjust)
- Where this recommendation is surfaced: new DB column, separate table, or
  in-app notification only
- The HR workflow: how does HR action or dismiss the recommendation

Do NOT write any code until I say "Proceed".
```

---

## Phase 5 — Expected Plan

- [ ] No automatic pay grade changes — HR must explicitly approve
- [ ] Recommendation stored (not just notified) so HR has an audit trail
- [ ] Score thresholds configurable per tenant (not hardcoded)
- [ ] Dismissed recommendations are recorded with who dismissed them and when

---

## Phase 5 — Verification Checklist

```bash
./mvnw test -Dtest="Performance*PayGrade*,ReviewCycle*"

# Confirm no automatic salary changes
grep -r "payGrade.setSalary\|updateSalary" src/main/java --include="*.java"
# Should return nothing from the new Phase 5 code
```

---

---

# PHASE 6 — Leave Accrual Engine

**Why:** Leave balances are currently static. In real Nigerian HR, annual leave
accrues monthly (e.g. 20 days/year = 1.67 days/month). Mid-year joiners should
get prorated balances. This phase adds a scheduled accrual job and prorated
allocation on new employee creation.

---

## Phase 6 — Start Prompt (paste into Claude Code)

```
Read these files before writing any code:

LEAVE MODULE:
- src/main/java/com/maestrohr/leave/entity/LeaveBalance.java
- src/main/java/com/maestrohr/leave/entity/LeaveType.java
- src/main/java/com/maestrohr/leave/service/LeaveService.java
- src/main/java/com/maestrohr/leave/repository/LeaveBalanceRepository.java

EMPLOYEE MODULE:
- src/main/java/com/maestrohr/employee/entity/Employee.java

TENANT/SUBSCRIPTION:
- src/main/java/com/maestrohr/tenant/entity/TenantSubscription.java

Once you have read all files, report back:

1. How is LeaveBalance currently created — manually by HR, or auto on employee
   creation?
2. Does LeaveType have an annualEntitlement field (total days per year)?
3. Is there a carryover cap field anywhere, or is carryover currently unlimited?
4. Does the project have any existing @Scheduled jobs? Show them.

Then propose:
- Monthly accrual job: runs 1st of each month, credits all active employees
  with (annualEntitlement / 12) per applicable leave type
- Pro-rata allocation: when an employee is created mid-year, calculate
  remaining months and credit accordingly
- Carryover logic: at year end, cap carryover at configured max (default 5 days)
  and forfeit the rest (log forfeiture for audit)
- How TenantContext is set inside @Scheduled jobs (this is a common bug — 
  scheduled jobs run outside a request context)

Do NOT write any code until I say "Proceed".
```

---

## Phase 6 — Expected Plan

- [ ] `@Scheduled` job explicitly sets and clears `TenantContext` for EACH
      tenant in the loop (not once at job start)
- [ ] Accrual is idempotent — running it twice in a month does not double credit
      (use a `lastAccrualDate` field or accrual log table)
- [ ] Pro-rata on new employee creation hooks into the `OfferAcceptedEvent`
      listener from Phase 3
- [ ] Carryover cap is per-tenant configurable, stored in `LeaveType` or a
      tenant settings table
- [ ] Flyway migration for any new columns

---

## Phase 6 — Verification Checklist

```bash
./mvnw test -Dtest="LeaveAccrual*,LeaveBalance*"

# Idempotency test must exist
grep -r "idempotent\|lastAccrualDate\|AccrualLog" src/test --include="*.java"

# TenantContext must be set inside the scheduler
grep -A20 "@Scheduled" src/main/java --include="*.java" -r | grep "TenantContext"
```

---

---

# PHASE 7 — Subscription Feature Gating (AOP)

**Why:** Feature flag checks are currently scattered as `if (plan.allows(X))`
throughout service methods. This is hard to audit and easy to miss. This phase
replaces scattered checks with a clean `@RequiresFeature` annotation enforced
by AOP — one place to change, consistent enforcement.

---

## Phase 7 — Start Prompt (paste into Claude Code)

```
Read these files before writing any code:

SUBSCRIPTION MODULE:
- src/main/java/com/maestrohr/subscription/entity/SubscriptionPlan.java
- src/main/java/com/maestrohr/subscription/service/SubscriptionService.java
- src/main/java/com/maestrohr/subscription/FeatureFlag.java (if exists)

EXAMPLES OF CURRENT GATING (find them):
Run this and show me the results:
grep -rn "plan.allows\|subscription.has\|featureEnabled\|planType ==" \
  src/main/java --include="*.java" | head -30

Once you have read all files and run the grep, report back:

1. List every location where feature checks currently exist in service classes
2. What is the SubscriptionPlan entity structure — is it an enum or a DB entity
   with feature flags as columns or a JSON field?
3. Is there an existing AOP dependency in pom.xml (spring-boot-starter-aop)?

Then propose:
- @RequiresFeature("FEATURE_NAME") annotation definition
- FeatureCheckAspect that intercepts methods annotated with @RequiresFeature,
  resolves the current tenant's plan, and throws FeatureNotAvailableException
  if the feature is not on their plan
- How FeatureNotAvailableException maps to an HTTP 402 or 403 response
- Migration plan: replace each existing plan.allows() call with @RequiresFeature

Do NOT write any code until I say "Proceed".
```

---

## Phase 7 — Expected Plan

- [ ] `@RequiresFeature` is a method-level annotation
- [ ] Aspect resolves tenant from `TenantContext` (not from method params)
- [ ] `FeatureNotAvailableException` returns HTTP 402 with a clear message
      (e.g. "Upgrade your plan to access payroll reporting")
- [ ] All old `plan.allows()` calls are removed, not duplicated
- [ ] Test: call a premium feature endpoint on a basic plan → expect 402

---

## Phase 7 — Verification Checklist

```bash
./mvnw test -Dtest="FeatureGating*,Subscription*"

# No more raw plan checks in service classes
grep -rn "plan.allows\|planType ==" src/main/java --include="*.java"
# Should return zero results
```

---

---

# PHASE 8 — Structured Logging + Sentry Grouping

**Why:** When something breaks in production for one tenant, right now you have
no fast way to filter logs by tenant. Sentry errors from 10 different tenants
get mixed into one noisy feed. This phase adds tenant ID to every log line via
MDC and groups Sentry errors by tenant.

---

## Phase 8 — Start Prompt (paste into Claude Code)

```
Read these files before writing any code:

LOGGING / CONFIG:
- src/main/resources/logback-spring.xml (or logback.xml)
- src/main/resources/application.yml
- src/main/java/com/maestrohr/auth/filter/JwtAuthFilter.java
- src/main/java/com/maestrohr/common/tenant/TenantContext.java

SENTRY:
- Check pom.xml for sentry-spring-boot-starter version
- src/main/java/com/maestrohr/MaestroHrApplication.java

Once you have read all files, report back:

1. What does the current logback pattern look like — is tenantId in it?
2. Is MDC (Mapped Diagnostic Context) used anywhere already?
3. What version of the Sentry Spring Boot starter is in pom.xml?
4. Is there a global exception handler (@ControllerAdvice)? Show it.

Then propose:
- Where in the filter chain to call MDC.put("tenantId", ...) and MDC.remove()
- The updated logback pattern string that includes tenantId
- Sentry tag configuration to add tenantId as a tag (not just in the breadcrumb)
  so Sentry's "Group by: tenantId" works in the dashboard
- A custom SentryEventProcessor that strips PII (employee names, salary values)
  from error payloads before they leave the server

Do NOT write any code until I say "Proceed".
```

---

## Phase 8 — Expected Plan

- [ ] `MDC.put("tenantId", ...)` is called AFTER TenantContext is set,
      and `MDC.remove("tenantId")` is in the same `finally` block as
      `TenantContext.clear()`
- [ ] Logback pattern updated: `%d{HH:mm:ss} [%X{tenantId}] %-5level %logger - %msg%n`
- [ ] Sentry tag set via `SentryEvent.setTag("tenantId", ...)` or equivalent
      for the installed Sentry version
- [ ] PII stripping in `BeforeSendCallback` — salary fields and employee names
      must not appear in Sentry payloads

---

## Phase 8 — Verification Checklist

```bash
./mvnw test -Dtest="Logging*,Sentry*"

# tenantId must appear in logback config
grep "tenantId\|%X{" src/main/resources/logback*.xml

# MDC must be cleared in finally
grep -A10 "MDC.put" src/main/java --include="*.java" -r | grep "finally\|MDC.remove"
```

---

---

## AFTER ALL PHASES — Final Integration Test

Once all 8 phases are complete, run this full-stack scenario test:

```
Tell Claude Code:

"Run a full end-to-end scenario test covering all integrated modules:

1. Create a tenant with a paid subscription plan
2. Post a job, receive application, mark offer as accepted
   → verify Employee record created with ONBOARDING status
   → verify welcome notification sent
   → verify leave balance prorated and created
3. Employee works for 20 days, has 2 approved unpaid leave days,
   1 absent day in attendance records
4. HR runs payroll for the month
   → verify unpaid leave deduction appears as a line item
   → verify attendance deduction appears as a line item
   → verify final net pay is mathematically correct in kobo
5. Complete a performance review cycle with a high score
   → verify pay grade recommendation is flagged for HR
6. HR initiates exit, completes clearance checklist
   → verify final settlement payroll run is triggered
   → verify leave encashment calculated correctly
   → verify Employee status is EXITED
7. Try to access a premium feature on a basic plan
   → verify 402 response
8. Check logs — every line must contain the tenantId in MDC
9. Try to access another tenant's employee record
   → verify 404 response (tenant isolation working)"
```

This scenario should pass entirely before you consider MaestroHR production-ready.

---

## NOTES FOR CLAUDE CODE

- All monetary values must remain in **kobo** (Long, not Double or BigDecimal)
- No module should import another module's Repository directly —
  always go through the service layer
- Every new DB column needs a Flyway migration script (next V number after V19)
- Keep `TenantContext` ThreadLocal clean — always clear in `finally`
- New @Scheduled jobs must set TenantContext per-tenant in the execution loop
- Do not add new dependencies without flagging them for my review first
