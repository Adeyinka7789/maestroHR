# MaestroHR - Multi-Tenant HR & Payroll SaaS Platform

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-6DB33F?style=flat&logo=springboot)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-17-007396?style=flat&logo=java)](https://www.oracle.com/java/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat&logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?style=flat&logo=redis&logoColor=white)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

## 📋 Overview

**MaestroHR** is a cloud-based, multi-tenant HR and Payroll SaaS platform designed specifically for **Nigerian Small and Medium Enterprises (SMEs)**. It automates the complete employee lifecycle—from onboarding and attendance tracking to statutory deductions and bulk salary disbursement via Paystack—while enforcing strict tenant data isolation using PostgreSQL Row Level Security (RLS).


## 🏗️ System Architecture

### High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    CLIENT LAYER                                      │
├─────────────────────────────┬─────────────────────────────┬─────────────────────────┤
│      Web Browser (UI)       │      Mobile Browser         │      API Client         │
│     /dashboard, /login      │    Responsive Design        │    Postman / cURL       │
└─────────────┬───────────────┴───────────────┬─────────────┴───────────┬─────────────┘
              │                               │                         │
              │  HTTPS / WSS                  │  HTTPS                  │  HTTPS
              ▼                               ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              REVERSE PROXY / LOAD BALANCER                           │
│                                   Nginx (Port 80/443)                                │
│                              SSL Termination / Rate Limiting                         │
└─────────────────────────────────────────┬───────────────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              APPLICATION LAYER (Port 8080)                          │
│                              Spring Boot 3.4.5 / Java 17                            │
├─────────────────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐   │
│  │   AUTH      │ │  EMPLOYEE   │ │  PAYROLL    │ │   LEAVE     │ │ ATTENDANCE  │   │
│  │  MODULE     │ │   MODULE    │ │   MODULE    │ │   MODULE    │ │   MODULE    │   │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘   │
│         │               │               │               │               │          │
│  ┌──────┴──────┐ ┌──────┴──────┐ ┌──────┴──────┐ ┌──────┴──────┐ ┌──────┴──────┐   │
│  │ NOTIFICA-   │ │  PAYSTACK   │ │  REPORTING  │ │    AUDIT    │ │  WEBHOOK    │   │
│  │   TION      │ │   CLIENT    │ │   MODULE    │ │   MODULE    │ │  HANDLER    │   │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘   │
│         │               │               │               │               │          │
│         └───────────────┴───────────────┴───────────────┴───────────────┘          │
│                                    │                                               │
│                          ┌─────────┴─────────┐                                     │
│                    JWT    │                   │    Session                         │
│                    Filter │                   │    Management                      │
│                          └─────────┬─────────┘                                     │
└────────────────────────────────────┼────────────────────────────────────────────────┘
                                     │
          ┌──────────────────────────┼──────────────────────────┐
          │                          │                          │
          ▼                          ▼                          ▼
┌─────────────────┐    ┌─────────────────────────┐    ┌─────────────────────────┐
│    CACHE LAYER  │    │      DATABASE LAYER     │    │    MESSAGE QUEUE LAYER   │
│                 │    │                         │    │                         │
│   Redis (Port 6379)  │   PostgreSQL 16         │    │   Kafka (Port 9092)      │
│                 │    │   (Port 5432)           │    │                         │
│ ┌─────────────┐ │    │ ┌─────────────────────┐ │    │ ┌─────────────────────┐ │
│ │ JWT         │ │    │ │   Tenants Table     │ │    │ │ payroll.payslip.    │ │
│ │ Blacklist   │ │    │ │   (Global)          │ │    │ │   generate          │ │
│ └─────────────┘ │    │ └─────────────────────┘ │    │ └─────────────────────┘ │
│ ┌─────────────┐ │    │ ┌─────────────────────┐ │    │ ┌─────────────────────┐ │
│ │ Pay Grade   │ │    │ │   Users Table       │ │    │ │ payroll.email.      │ │
│ │ Cache       │ │    │ │   (RLS enforced)    │ │    │ │   dispatch          │ │
│ └─────────────┘ │    │ └─────────────────────┘ │    │ └─────────────────────┘ │
│ ┌─────────────┐ │    │ ┌─────────────────────┐ │    │ ┌─────────────────────┐ │
│ │ Tax Config  │ │    │ │   Employees Table   │ │    │ │ payroll.sms.        │ │
│ │ Cache       │ │    │ │   (RLS enforced)    │ │    │ │   dispatch          │ │
│ └─────────────┘ │    │ └─────────────────────┘ │    │ └─────────────────────┘ │
│ ┌─────────────┐ │    │ ┌─────────────────────┐ │    │                         │
│ │ Session     │ │    │ │   Payroll Tables    │ │    │                         │
│ │ Data        │ │    │ │   (RLS enforced)    │ │    │                         │
│ └─────────────┘ │    │ └─────────────────────┘ │    │                         │
└─────────────────┘    └───────────┬─────────────┘    └─────────────────────────┘
                                   │
                                   │  RLS Enforcement
                                   │  SET app.current_tenant = '<uuid>'
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              EXTERNAL SERVICES                                      │
├─────────────────────────┬─────────────────────────┬─────────────────────────────────┤
│                         │                         │                                 │
│    Paystack API         │    Termii API           │    SMTP Server                  │
│    (Bulk Transfers)     │    (SMS Gateway)        │    (Email Delivery)              │
│                         │                         │                                 │
└─────────────────────────┴─────────────────────────┴─────────────────────────────────┘
```

### Request Lifecycle Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              COMPLETE REQUEST LIFECYCLE                                 │
└─────────────────────────────────────────────────────────────────────────────────────────┘

Client                    Nginx                    Spring Boot                PostgreSQL
  │                         │                         │                          │
  │  1. POST /api/payroll   │                         │                          │
  │────────────────────────>│                         │                          │
  │                         │  2. Proxy to :8080      │                          │
  │                         │────────────────────────>│                          │
  │                         │                         │                          │
  │                         │                         │  3. JwtAuthFilter        │
  │                         │                         │  Validate Bearer Token   │
  │                         │                         │  Extract email/role      │
  │                         │                         │                          │
  │                         │                         │  4. TenantFilter         │
  │                         │                         │  Extract tenantId from   │
  │                         │                         │  JWT claims              │
  │                         │                         │  TenantContext.set()     │
  │                         │                         │                          │
  │                         │                         │  5. SecurityContext      │
  │                         │                         │  Set Authentication      │
  │                         │                         │                          │
  │                         │                         │  6. Controller           │
  │                         │                         │  @PreAuthorize check     │
  │                         │                         │                          │
  │                         │                         │  7. Service Layer        │
  │                         │                         │  @Transactional starts   │
  │                         │                         │                          │
  │                         │                         │  8. Repository Query     │
  │                         │                         │─────────────────────────>│
  │                         │                         │                          │
  │                         │                         │  9. HibernateRLSInterceptor
  │                         │                         │  fires before query:     │
  │                         │                         │  SET app.current_tenant  │
  │                         │                         │  = '<uuid>'              │
  │                         │                         │                         │
  │                         │                         │  10. SELECT * FROM       │
  │                         │                         │      employees WHERE    │
  │                         │                         │      tenant_id =        │
  │                         │                         │      current_setting()  │
  │                         │                         │─────────────────────────>│
  │                         │                         │                         │
  │                         │                         │  11. Rows filtered      │
  │                         │                         │      by RLS policy      │
  │                         │                         │<─────────────────────────│
  │                         │                         │                         │
  │                         │  12. JSON Response      │                         │
  │                         │<────────────────────────│                         │
  │  13. HTTP Response      │                         │                         │
  │<────────────────────────│                         │                         │
  │                         │                         │  14. TenantContext.clear()│
  │                         │                         │  (finally block)        │
  │                         │                         │                         │
```


### 🎯 Target Audience
- Nigerian SMEs with 5 to 500 employees
- Companies currently managing payroll manually via spreadsheets or informal bookkeeping
- HR departments needing digitized employee records and leave management

### ✨ Key Features

| Feature | Description |
|---------|-------------|
| **Multi-Tenant Architecture** | Complete data isolation with PostgreSQL RLS |
| **Employee Management** | Full CRUD operations, department & pay grade management |
| **Nigerian Payroll Engine** | Automated PAYE, Pension (8%/10%), NHF (2.5%), NSITF (1%) calculations |
| **Leave Management** | Configurable leave types, request/approval workflow, balance tracking |
| **Attendance Tracking** | Daily attendance recording with proration support |
| **Paystack Integration** | Bulk salary disbursement, bank account verification |
| **Notifications** | Email (PDF payslips) + SMS (Termii) + In-app notifications |
| **Reporting** | PDF & Excel reports (Payroll Summary, PAYE Schedule, Pension Schedule, etc.) |
| **Role-Based Access** | HR_ADMIN, FINANCE_OFFICER, DEPT_MANAGER, EMPLOYEE roles |
| **Employee Self-Service** | Employees can view payslips, submit leave requests, update profile |

## 🏗️ System Architecture

### Technology Stack

| Layer | Technology | Version |
|-------|------------|---------|
| **Framework** | Spring Boot | 3.4.5 |
| **Language** | Java | 17 |
| **Database** | PostgreSQL | 16 |
| **ORM** | Hibernate / Spring Data JPA | - |
| **Security** | Spring Security + JWT | - |
| **Cache** | Redis | 7 |
| **Frontend** | Thymeleaf + Tailwind CSS | - |
| **PDF Generation** | Flying Saucer / iText | 9.1.22 |
| **Excel Export** | Apache POI | - |
| **Payment** | Paystack API | - |
| **SMS** | Termii API | - |
| **Build Tool** | Maven | 3.9+ |

### Multi-Tenancy with PostgreSQL RLS

```
HTTP Request → JwtAuthFilter → TenantContext (ThreadLocal) → DataSourceConfig → SET app.current_tenant → PostgreSQL RLS → Isolated Data
```

Every tenant-scoped table has RLS policies enforcing:
```sql
USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
```

### Project Structure

```
src/main/java/com/admtechhub/maestrohr/
├── auth/                    # JWT authentication, TenantContext
├── common/                  # BaseEntity, ApiResponse, Exception handling
├── config/                  # Security, DataSource, RLS configuration
├── tenant/                  # Tenant management, subscriptions
├── employee/                # Employees, Departments, PayGrades
├── payroll/                 # Payroll engine, calculators, runs
├── leave/                   # Leave types, requests, balances
├── attendance/              # Attendance tracking
├── paystack/                # Paystack API integration
├── notification/            # Email, SMS, in-app notifications
├── reporting/               # PDF & Excel report generation
├── webhook/                 # Paystack webhook handler
└── web/                     # Thymeleaf web controllers & templates

src/main/resources/
├── application.yml          # Application configuration
├── db/migration/            # Flyway migrations (V1-V8)
└── templates/               # Thymeleaf HTML templates
```

## 🚀 Getting Started

### Prerequisites

- **Java 17** (JDK 17+)
- **PostgreSQL 16** (with RLS support)
- **Redis** (or Memurai on Windows)
- **Maven 3.9+**

### Installation

1. **Clone the repository**
```bash
git clone https://github.com/yourusername/maestrohr.git
cd maestrohr
```

2. **Create PostgreSQL database**
```sql
CREATE DATABASE maestrohr_db;
```

3. **Configure application.yml**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/maestrohr_db
    username: postgres
    password: your_password
    
paystack:
  secret-key: sk_test_xxxxxxxxxxxxxxxxxxxx
  
termii:
  api-key: your-termii-api-key
  sender-id: MaestroHR
```

4. **Run Flyway migrations**
```bash
mvn flyway:migrate
```

5. **Build and run**
```bash
mvn clean package
java -jar target/maestrohr.jar
```

Or using Maven:
```bash
mvn spring-boot:run
```

6. **Access the application**
- Web UI: `http://localhost:8080/login`
- API Base: `http://localhost:8080/api`

### Default Credentials

After registration, use these credentials for testing:

| Email | Password | Role |
|-------|----------|------|
| michael@admtechhub.com | password123 | HR_ADMIN |

## 📡 API Endpoints

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/register` | Register new tenant and admin |
| POST | `/api/auth/login` | Login with email/password |

### Employees

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/employees` | Create employee (with user account) |
| GET | `/api/employees` | List employees (paginated) |
| GET | `/api/employees/{id}` | Get employee by ID |
| PUT | `/api/employees/{id}` | Update employee |
| DELETE | `/api/employees/{id}/terminate` | Terminate employee |
| GET | `/api/employees/search` | Search employees |
| GET | `/api/employees/stats/active-count` | Active employee count |

### Payroll

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/payroll/initiate` | Start new payroll run |
| POST | `/api/payroll/{id}/compute` | Calculate payroll for all employees |
| POST | `/api/payroll/{id}/submit` | Submit for approval |
| POST | `/api/payroll/{id}/approve` | Approve payroll (FINANCE_OFFICER) |
| POST | `/api/payroll/{id}/reject` | Reject payroll with reason |
| GET | `/api/payroll/{id}` | Get payroll run details |
| GET | `/api/payroll` | List all payroll runs |
| GET | `/api/payroll/{id}/entries` | Get employee entries |
| GET | `/api/payroll/{id}/summary` | Get summary statistics |
| POST | `/api/payroll/{id}/disburse` | Initiate salary disbursement |

### Leave Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/leave/types` | List leave types |
| POST | `/api/leave/requests` | Submit leave request |
| GET | `/api/leave/requests/employee/{id}` | Employee's requests |
| GET | `/api/leave/requests/pending` | Pending approvals |
| POST | `/api/leave/requests/{id}/approve` | Approve request |
| POST | `/api/leave/requests/{id}/reject` | Reject request |
| GET | `/api/leave/balance` | Check leave balance |

### Reports

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/reports/payroll-summary` | Monthly payroll summary (PDF/Excel) |
| GET | `/api/reports/paye-schedule` | PAYE Schedule Form A (PDF/Excel) |
| GET | `/api/reports/pension-schedule` | Pension schedule (PDF/Excel) |
| GET | `/api/reports/nhf-schedule` | NHF schedule (PDF/Excel) |
| GET | `/api/reports/headcount` | Employee headcount report |
| GET | `/api/reports/leave-balance` | Leave balance report |
| GET | `/api/reports/salary-history/{employeeId}` | Salary history report |
| GET | `/api/reports/audit-trail` | Audit trail report |
| GET | `/api/reports/payslip/{employeeId}/{payrollRunId}` | Individual payslip PDF |

## 💰 Nigerian Statutory Calculations

### PAYE Tax Bands (Annual)

| Taxable Income Band | Rate |
|---------------------|------|
| First NGN 300,000 | 7% |
| Next NGN 300,000 | 11% |
| Next NGN 500,000 | 15% |
| Next NGN 500,000 | 19% |
| Next NGN 1,600,000 | 21% |
| Above NGN 3,200,000 | 24% |

### Deduction Order

1. **Gross Salary** = Basic + Housing + Transport + Other Allowances
2. **Pension (Employee)** = 8% of (Basic + Housing + Transport)
3. **Pension (Employer)** = 10% of (Basic + Housing + Transport)
4. **NHF** = 2.5% of Basic Salary
5. **Gross Taxable** = Gross - Pension(Emp) - NHF
6. **CRA** = Higher of NGN 200,000 or (1% of Gross + 20% of Gross)
7. **Taxable Income** = Gross Taxable - CRA
8. **PAYE** = Apply progressive bands to annualized taxable income
9. **NSITF** = 1% of Gross (Employer only)
10. **Net Salary** = Gross - Pension(Emp) - NHF - PAYE

> **Note:** All monetary values are stored as `BIGINT` in **kobo** (1 NGN = 100 kobo). No floating-point arithmetic is used for financial calculations.

## 🔒 Security Features

- **JWT Authentication** with 24-hour access tokens and 7-day refresh tokens
- **PostgreSQL RLS** for tenant isolation (second line of defense)
- **BCrypt password encoding**
- **Account lockout** after 5 failed login attempts (30-minute lock)
- **BVN/NIN encryption** at rest using AES-256
- **HTTPS enforcement** (configurable)
- **Rate limiting** on auth endpoints
- **CORS** configured for allowed origins only

## 🧪 Testing

```bash
# Run unit tests
mvn test

# Run integration tests
mvn verify

# Run with test coverage
mvn jacoco:report
```

## 📊 Performance Benchmarks

| Operation | Target | Achieved |
|-----------|--------|----------|
| Payroll computation for 100 employees | < 10s | ✅ ~3s |
| API response time (p95) | < 500ms | ✅ |
| PDF payslip generation | < 3s | ✅ |
| Bulk transfer initiation (100 recipients) | < 5s | ✅ |

## 🐳 Deployment

### Production Deployment (TrueHost VPS)

```bash
# Build
mvn clean package -DskipTests

# Copy to server
scp target/maestrohr.jar user@server:/var/www/maestrohr/

# Run as systemd service
sudo systemctl start maestrohr
sudo systemctl enable maestrohr

# Nginx reverse proxy configuration
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

### Environment Variables

```bash
# Database
DB_URL=jdbc:postgresql://localhost:5432/maestrohr_db
DB_USERNAME=postgres
DB_PASSWORD=your_password

# JWT
JWT_SECRET=your-jwt-secret-key

# Paystack
PAYSTACK_SECRET_KEY=sk_live_xxxxxxxxxxxxx

# Termii SMS
TERMII_API_KEY=your-termii-api-key

# Email (optional)
MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-app-password
```

## 📈 Roadmap

### Completed ✅

- [x] Multi-tenant foundation with RLS
- [x] JWT authentication & authorization
- [x] Employee, department, pay grade management
- [x] Nigerian payroll engine with statutory calculations
- [x] Paystack bulk disbursement integration
- [x] Leave & attendance management
- [x] Email, SMS, and in-app notifications
- [x] Complete Thymeleaf web UI
- [x] PDF & Excel reporting

### Future Enhancements 🔄

- [ ] Biometric attendance integration
- [ ] Mobile native apps (iOS/Android)
- [ ] Pension remittance API integration
- [ ] Recruitment / ATS module
- [ ] Performance management / OKRs
- [ ] Multi-currency payroll
- [ ] Advanced analytics dashboard

## 🤝 Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 👨‍💻 Author

**Michael Adeniran** - *Lead Developer*
- GitHub: [@michaeladeniran](https://github.com/michaeladeniran)
- Company: ADM Tech Hub, Lagos, Nigeria

## 🙏 Acknowledgments

- [Spring Boot](https://spring.io/projects/spring-boot)
- [Paystack](https://paystack.com) for payment infrastructure
- [Termii](https://termii.com) for SMS services
- [PostgreSQL](https://www.postgresql.org) for RLS multi-tenancy

---

## 📞 Support

For support, email: support@admtechhub.com or open an issue in the GitHub repository.

---

**Built with ❤️ for Nigerian SMEs**
