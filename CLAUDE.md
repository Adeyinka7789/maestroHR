# Architecture Notes

## Dual Service Pattern
This codebase has two parallel service layers for several domains:
- `*Service.java` — REST API logic (used by `/api/**` endpoints)
- `*ListService.java` — Server-rendered HTMX view logic (used by `/htmx/**` endpoints)

These are INTENTIONALLY separate for: Employee, Attendance, PayGrade, Loan, Payroll.

**IMPORTANT**: When fixing a bug (especially security/filtering logic), always trace from the actual controller endpoint (`grep` the URL path in Controller files) down to the real service method being called. Do not assume the most obviously-named service method is the one in use - both services may have similarly-named methods that diverge in behavior.

Known historical bug: A security fix for leave request visibility was initially applied to `LeaveService.getAllLeaveRequests()`, which is only used by the REST API. The actual web UI endpoint `/htmx/leave` uses `LeaveListService.assemble()`, which had no filtering at all. Always verify which service a controller actually calls before patching.
