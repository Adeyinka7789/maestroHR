# MaestroHR: Product Documentation & Specifications

## 1. Executive Summary
MaestroHR is a cloud-based, multi-tenant HR and Payroll SaaS platform designed for Nigerian Small and Medium Enterprises (SMEs). It automates the full employee lifecycle while enforcing strict tenant data isolation using PostgreSQL Row Level Security (RLS).

## 2. Target Users
- **Super Admin**: Platform management, billing, and health.
- **HR Admin**: Employee records, leave, and payroll processing.
- **Finance Officer**: Payroll approval and statutory filing.
- **Department Manager**: Team visibility and leave approvals.
- **Employee**: Self-service (payslips, leave requests).

## 3. Core Modules
- Tenant & Subscription Management
- Employee Management (onboarding, profiles, grades)
- Payroll Engine (Nigerian statutory logic: PAYE, Pension, NHF, NSITF)
- Leave & Attendance Management
- Bulk Disbursement (Paystack API)
- Reporting & Analytics

## 4. Visual Identity Requirements
- **Professional & Trustworthy**: Since it handles payroll and sensitive employee data.
- **Clean & Modern**: SME-focused but robust.
- **Nigerian Context**: References to local banks, statutory bodies (PenCom, NHF), and Paystack integration.

## 5. Screen List (Phase 1)
1. **Super Admin Dashboard**: Overview of tenants, revenue (MRR), and system health.
2. **Tenant Management (Super Admin)**: List of companies, subscription statuses, and RC numbers.
3. **HR Admin Dashboard**: Employee headcount, upcoming leaves, and payroll status.
4. **Employee Profile (HR Admin)**: Detailed view including bank details (Paystack verified) and statutory info.
5. **Payroll Processing (HR Admin)**: Draft run table with gross/net/deductions breakdown.
6. **Employee Self-Service (Mobile)**: Payslip view and leave request form.