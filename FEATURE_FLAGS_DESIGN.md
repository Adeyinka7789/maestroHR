# Feature Flags — Redesign & Extraction Plan

Status: **PROPOSAL (awaiting approval)** · Target: a robust, decoupled flag engine that can later be lifted out as a standalone library.

---

## 1. Goal

Make the feature-flag system:

1. **Robust** — no fail-open/fail-closed inconsistencies, correct messaging, works inside and outside a web request.
2. **Easy to use** — "define a flag once, check it anywhere" with a typed key, in the spirit of Django settings / Celery task registration. No ceremony at the call site.
3. **Extractable** — the resolution engine has zero dependencies on MaestroHR domain types (billing, tenants, audit, Spring Security), so it can become a library other developers drop in.

Explicit decision: **plan-based entitlement stays separate from the flag engine.** Entitlement (what a paid plan includes) is app-specific billing logic. The flag engine (on/off, rollout, targeted overrides) is the reusable part. The app composes the two.

---

## 2. Current architecture (as-is)

```
@RequiresFeature / featureFlagService.isEnabled()
                 │
                 ▼
        FeatureFlagService.isEnabled(feature)     ← ANDs the two gates
                 │
      ┌──────────┴───────────┐
      ▼                      ▼
PlatformFlagService     SubscriptionService.hasFeature()
(flag engine)           (billing / plan matrix)
      │                      │
platform_flags          tenant_subscriptions
feature_flag_overrides  SubscriptionPlan (enum matrix)
```

**Flag engine resolution order** (`PlatformFlagService.isEnabledForTenant`), first match wins:

1. No `platform_flags` row → `false` (fail-closed)
2. Global `enabled = false` → `false` (absolute kill switch)
3. Tenant override → its value
4. Plan override → its value
5. `rollout_percentage < 100` (needs tenant) → consistent-hash bucket
6. else → `true`

**Strengths to preserve:** the layered override + rollout + kill-switch model, fail-closed engine default, SUPER_ADMIN exemption in the aspect, request-scoped caching, audit-on-write.

---

## 3. Problems to fix

### 3.1 Robustness (fix regardless of extraction)

| # | Problem | Fix |
|---|---------|-----|
| R1 | **Fail-open vs fail-closed disagree.** Engine treats absent flag as *disabled*; `ActiveFeaturesController` (`getOrDefault(name, true)`) and V30/V34 SQL comments assume *enabled*. Boot-ordering hazard: a check before `PlatformFlagSeeder` finishes fails closed. | Single documented policy: **unknown flag → configurable default (default `false`), decided in one place.** Make `/api/features/active` use the same resolver instead of its own `getOrDefault` logic. Seed flags via migration (not only the runtime seeder) so they exist before first request. |
| R2 | **Wrong 402 message for kill-switched features.** A globally-off or rollout-excluded feature throws `FeatureNotAvailableException` → *"Upgrade your plan…"*, which is misleading. | Distinguish the two causes. Entitlement miss → 402 "upgrade". Flag off/rollout → 404/403 "not available" (feature simply doesn't exist for you). See §5.3. |
| R3 | **No caching outside a web request.** Jobs/async iterating tenants query per flag per call. | Cache abstraction that works with or without a request context (see §5.2). |
| R4 | **`isEnabled` returns false whenever `tenantId == null`** even if the platform flag is on. Surprising in non-request contexts. | Make the null-context behavior explicit and intentional per gate (engine can resolve global flags with no tenant; entitlement obviously can't). |
| R5 | **Override on a missing flag row is a silent no-op** (resolution bails at flag==null before consulting overrides). | `createOverride` validates the flag exists (or auto-creates the row), consistent with `setRolloutPercentage`. |
| R6 | **Stringly-typed keys; weak hash distribution** (`String.hashCode()`). | Typed keys (§5.1). Keep `hashCode` bucketing for stability, or move to a documented stable hash — decide during impl (bucket stability across restarts is the hard requirement). |

### 3.2 Coupling that blocks extraction

`PlatformFlagService` currently imports/uses: `AuditTrailService`, `SubscriptionFeature`, `TenantContext` (indirectly via `FeatureFlagService`), `SecurityContextHolder`, `RequestContextHolder`. Each becomes a pluggable seam (§5).

---

## 4. Target usage (the "Django-like" developer experience)

Define once (typed, discoverable), check anywhere with no ceremony:

```java
// Definition — one place, self-documenting.
public enum AppFeature implements FlagKey {
    LEAVE_MANAGEMENT,
    ATTENDANCE_TRACKING,
    DOCUMENT_VAULT,
    HARDWARE_SYNC;
    public String key() { return name(); }
}
```

```java
// Declarative gate (unchanged call style, clearer semantics).
@RequiresFeature(AppFeature.LEAVE_MANAGEMENT)
public class LeaveController { ... }

// Imperative check.
if (flags.isOn(AppFeature.DOCUMENT_VAULT)) { ... }
```

```java
// App composition: flag engine AND billing entitlement, kept separate.
boolean available = flags.isOn(feature) && entitlement.includes(tenantId, feature);
```

Library consumer (another project) supplies three small implementations and gets the whole rollout/override/kill-switch engine for free:

```java
new FlagEngine(flagStore, contextResolver, auditListener /* optional */);
```

---

## 5. Proposed design

### 5.1 Reusable engine package (`…flags.core` → future library)

Zero domain imports. Public surface:

- `FlagKey` — interface with `String key()`. App enums implement it.
- `FlagEngine` — the resolver. Method `boolean isOn(String key)` / `isOn(FlagKey)`. Runs the 6-step layered resolution.
- `FlagStore` (SPI) — persistence: `findFlag(name)`, `findOverride(name, type, value)`, `save…`. Default JPA impl provided; consumers can back it with anything.
- `FlagContextResolver` (SPI) — supplies the current `target` (tenant id) and optional `bucketKey`, with **no** dependency on `TenantContext`. MaestroHR's impl reads `TenantContext`.
- `FlagAuditListener` (SPI, optional no-op default) — receives change events. MaestroHR's impl calls `AuditTrailService`.
- `FlagCache` (SPI) — `get/putForRequest`. Two impls: request-scoped (current behavior) and a short-TTL in-memory fallback for non-request contexts (fixes R3). Chosen automatically.
- Unknown-flag policy is a single injected setting (fixes R1).

### 5.2 Caching

`FlagCache` interface with:
- `RequestScopedFlagCache` — current `RequestContextHolder` behavior (web requests).
- `TtlFlagCache` — small time-boxed cache used when there is no request context (jobs), configurable TTL, off by default in tests. Resolves R3 without changing per-request semantics.

### 5.3 Error semantics (R2)

- `FeatureNotAvailableException` → keep **only** for entitlement misses (plan doesn't include it) → HTTP 402 "upgrade your plan".
- New `FeatureDisabledException` for flag-off / rollout-excluded → HTTP 404 (or 403; decide during impl) "feature not available". The aspect throws the right one based on *why* the composite check failed.

### 5.4 App layer (stays in MaestroHR, not in the library)

- `EntitlementResolver` — thin wrapper over `SubscriptionService.hasFeature` / `getPlanName`.
- `FeatureAccessService` (replaces today's `FeatureFlagService`) — composes `flagEngine.isOn(feature) && entitlement.includes(...)`, and is what `@RequiresFeature`'s aspect calls. This is the only place the two concepts meet.

### 5.5 Package move (final shape)

```
com.admtechhub.maestrohr.flags.core      ← extractable engine (SPIs + FlagEngine)
com.admtechhub.maestrohr.flags.jpa       ← default FlagStore (platform_flags, feature_flag_overrides)
com.admtechhub.maestrohr.flags.spring    ← @RequiresFeature, FeatureCheckAspect, request cache
com.admtechhub.maestrohr.subscription    ← EntitlementResolver, FeatureAccessService (app glue)
```

No DB schema change required for the core refactor; `platform_flags` / `feature_flag_overrides` stay as-is.

---

## 6. Execution phases

Small, reviewable, each green before the next.

- **Phase 0 — this doc.** ✅ approved.
- **Phase 1 — robustness fixes in place (no restructure).** ✅ (commit `218571d`). R1, R2, R5, R4. Unit tests green (`PlatformFlagServiceTest`, `FeatureCheckAspectTest`); integration slice not run (no DB in dev env).
- **Phase 2 — introduce SPIs + typed `FlagKey`, no package move yet.** ✅. Extracted `FlagStore` (+ `JpaFlagStore`) and `FlagAuditListener` (+ `AuditTrailFlagListener`); `PlatformFlagService` now depends on those two SPIs, not Spring Data / `AuditTrailService`. Added `FlagKey` (implemented by `SubscriptionFeature`) + `isEnabled(FlagKey)`. Behavior identical; `PlatformFlagServiceTest` rewritten to mock the SPIs (14/0/0).
  - **Scope refinement:** `FlagContextResolver` is deferred to Phase 4. The engine's `isEnabledForTenant(name, tenantId, planName)` already takes targeting context as explicit params — it has *no* `TenantContext` coupling to remove. The resolver seam only matters once we want a no-arg `engine.isOn(key)`, which pairs naturally with the entitlement split. `FlagCache` moves wholly to Phase 3.
  - **Pre-extraction debt noted:** `FlagStore` still trafficks in the JPA-annotated `PlatformFlag`/`FeatureFlagOverride` entities. Replace with framework-free records before the library is cut.
- **Phase 3 — caching seam (R3).** ✅. `FlagCache` SPI + `DefaultFlagCache`: request-scoped when a request is bound, short-TTL (default 5s, `maestrohr.flags.cache-ttl-ms`) process cache otherwise — so background jobs no longer query the flag tables once per flag per tenant. All `RequestContextHolder` plumbing moved out of the engine behind the SPI; read path unified on `findAllFlags`. `DefaultFlagCacheTest` covers request-scope + TTL expiry via an injected clock. Verified against the real DB (see below). (R6 hash-distribution left as-is — `String.hashCode` bucketing is stable across JVMs, which is the property that matters; revisit only if distribution proves uneven.)
- **Phase 4 — split entitlement from engine.** ✅. `FlagContextResolver` SPI (+ `TenantFlagContextResolver`) gives the engine a context-resolved `isOn(FlagKey)`. `EntitlementResolver` (+ `PlanEntitlementResolver`) isolates the billing half. New `FeatureAccessService` composes flag-check AND entitlement (resolving context once, keeping the 402/404 split); the fused `FeatureFlagService` is deleted, its callers (`FeatureCheckAspect`, `EmployeesController`) repointed. The engine now depends only on the four SPIs (`FlagStore`, `FlagAuditListener`, `FlagCache`, `FlagContextResolver`) — entitlement lives entirely in the app.
- **Phase 5 — package move to `flags`.** ✅ (structural half). Extractable core moved to `com.admtechhub.maestrohr.flags` (engine, 4 SPIs, `FlagKey`, `DefaultFlagCache`, model entities) with a `package-info.java` documenting the boundary; MaestroHR adapters + composition stay in `..subscription`. Behavior-preserving; verified against the real DB. Rename `flags` → `wunmi` root happens at the actual repo cut.
  - **Deferred to the cut (documented finding):** the record purification is NOT done, because `PlatformFlag`/`FeatureFlagOverride` feed the admin Thymeleaf view via `${f.name}`/`${f.enabled}` (JavaBean getters) — Java `record`s expose `name()` not `getName()`, so purifying now would break the view. The framework-free swap must be paired with reworking the admin view's property access; it's cleanest to do when copying the core into the `wunmi` repo. Until then the model carries `jakarta.persistence` + Lombok, which is inert for a non-JPA consumer.

Extraction to a real published library is a later step, unlocked by Phases 1–5 but not required to land value now.

---

## 7. Open questions for approval

1. **Error code for flag-off** (§5.3): 404 (feature invisible) vs 403 (forbidden)? I lean 404 — a killed feature shouldn't advertise itself.
2. **Unknown-flag default** (R1): keep fail-closed (`false`) as the global default? I recommend yes, with the seeder + migration guaranteeing known flags exist.
3. **Phase batching**: land Phase 1 (robustness) as its own commit/PR before any restructuring, or bundle 1–2 together?
```
