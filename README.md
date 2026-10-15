![AudEx logo](brand/exports/logo/audex-lockup.png)

# AudEx API

A Spring Boot service for the AudEx housekeeping audit app, built with Java 21 and Maven. It stores accounts and synced audits in PostgreSQL, applies the shared scoring rules, and can store audit photos in an S3-compatible bucket. The mobile-first web app is maintained separately in **`audex-web`**.

**Sync audits safely** · **Score consistently** · **Manage accounts and photos**

---

## At a glance

The API receives finalized audits from the web app, validates their answers and rule set, and calculates each score on the server. Client-generated IDs make audit and photo uploads safe to retry after a dropped connection. Account and recovery endpoints support sign-in, while optional object storage handles audit photos.

> **Project status:** scoring and authentication have plain-Java harnesses. The Spring controllers, database migrations and photo-storage integration still need end-to-end validation against the target services before production use.

## Contents

- [Get started](#get-started)
- [What the API does](#what-the-api-does)
- [Project layout](#project-layout)
- [Scoring and shared data](#scoring-and-shared-data)
- [API endpoints](#api-endpoints)
- [Accounts and security](#accounts-and-security)
- [Deploy to Railway](#deploy-to-railway)
- [Current limitations](#current-limitations)

## Get started

### Requirements

- Java 21
- Maven
- PostgreSQL for running the service locally

### Run locally

Start PostgreSQL, then run the tests and service:

```bash
mvn test
mvn spring-boot:run
```

By default, the service connects to `jdbc:postgresql://localhost:5432/audex` with username and password `audex`, and listens on port `8080`. Railway's Postgres variables are used directly to construct the JDBC connection; `DATABASE_URL` can still override it when supplied:

| Variable | Purpose |
| --- | --- |
| `PGHOST`, `PGPORT` | PostgreSQL host and port |
| `PGDATABASE` or `POSTGRES_DB` | Database name (`PGDATABASE` takes precedence) |
| `PGUSER` or `POSTGRES_USER` | Database username (`PGUSER` takes precedence) |
| `PGPASSWORD` or `POSTGRES_PASSWORD` | Database password (`PGPASSWORD` takes precedence) |
| `PGDATA` | PostgreSQL server's data directory; not used by this API client |
| `DATABASE_URL` | Optional full JDBC URL override |
| `PORT` | HTTP port (default `8080`) |
| `AUDEX_APP_URL` | Web app origin used in account emails |
| `AUDEX_ALLOWED_ORIGINS` | Browser origin allowed to call the API |

Flyway applies the SQL migrations at startup. Keep real credentials in environment configuration; never commit them or a `.env` file.

## What the API does

- **Audit sync:** validate finalized audits and save them using client-generated IDs, so retries update rather than duplicate records.
- **Server-side scoring:** calculate status and percentage from the submitted answers and versioned rule set instead of trusting a client-calculated score.
- **Account management:** support initial account setup, sign-in, profile updates, password changes, sign-out and account recovery.
- **Photo storage:** optionally save JPEG, PNG or WebP audit photos in an S3-compatible bucket after the audit has synced.
- **Database migrations:** use Flyway to manage PostgreSQL schema changes.

## Project layout

| Path | Purpose |
| --- | --- |
| `src/main/java/.../scoring/` | Scoring engine, score results, rule sets and checklist catalogue |
| `src/main/java/.../auth/` | Accounts, sessions, password and email recovery, mail and authentication |
| `src/main/java/.../api/` | Audit sync, photo upload and CORS configuration |
| `src/main/java/.../storage/` | Optional S3-compatible photo storage |
| `src/main/resources/db/migration/` | Flyway schema migrations |
| `src/main/resources/application.yml` | Local defaults and environment-based configuration |
| `shared/` | Checklist template, rule sets and scoring fixtures shared with `audex-web` |
| `tools/` | Plain-Java test harnesses and shared-data generation utilities |
| `brand/exports/` | Exported AudEx brand assets |

## Scoring and shared data

| Rule set | Status | Summary |
| --- | --- | --- |
| `WPC_V1` | Active default | WPC thresholds (95% / 85%) with a Critical Non-Compliant override |
| `WPC_V2_PROPOSED` | Proposed | Adds review caps for Critical Partial, 3+ Major failures, a high-risk section below 85%, and N/A above 10% |

The API and `audex-web` contain copies of the same `shared/` directory. When the checklist, scoring rules or fixtures change, update both copies. Both repositories should test against the same `scoring-fixtures.json` so drift is caught.

To rebuild the shared data from the WPC workbook:

```bash
python3 tools/generate_seed.py <WPC_Health_Care_Housekeeping_Audit_Aligned.xlsx> shared
```

Scores use integer tenths of a percent and half-point increments so Java and TypeScript compare results consistently. Each audit retains the rule set it was scored under.

## API endpoints

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/api/auth/status` | Check whether first-account setup is required |
| `POST` | `/api/auth/setup` | Create the first account |
| `POST` | `/api/auth/login` | Sign in |
| `POST` | `/api/auth/logout` | Sign out the current session |
| `POST` | `/api/auth/logout-all` | Sign out all sessions |
| `POST` | `/api/auth/forgot-username` | Request username recovery by email |
| `POST` | `/api/auth/forgot-password` | Request a password-reset email |
| `POST` | `/api/auth/reset-password` | Set a new password using a reset token |
| `GET`, `PATCH` | `/api/me` | Read or update the signed-in user's profile |
| `POST` | `/api/me/password` | Change the signed-in user's password |
| `PUT` | `/api/audits/{id}` | Validate, score and sync a finalized audit |
| `PUT` | `/api/audits/{auditId}/photos/{photoId}` | Upload a photo after its audit has synced |
| `GET` | `/api/photos/{photoId}` | Retrieve an uploaded photo |

Protected endpoints use a bearer token in the `Authorization` header. Audit uploads require a reason for every `N/A` response; the server validates checklist items and recalculates the score. Photos are limited to 10 MB and must be JPEG, PNG or WebP. Photo endpoints return an error if bucket storage is not configured.

## Accounts and security

- Passwords are hashed with BCrypt (cost 12). The policy allows 10–128 characters and rejects common passwords, repeated characters, and passwords containing the username or email name.
- Session tokens contain 256 bits of randomness; only a SHA-256 hash is stored. Sessions expire after 30 days of inactivity or 90 days maximum.
- Password-reset tokens are single-use, expire after 30 minutes, and are stored as hashes. A new reset request invalidates older tokens; a successful reset signs out all sessions and sends a notice.
- Rate limits cover sign-in attempts, recovery requests and password confirmations. They are held in memory and are suitable for one server instance; move them to shared storage before running multiple instances.
- Username and password recovery use the same response whether or not the account exists.

## Deploy to Railway

Create a Railway service from this repository; Railway detects the Maven project. Configure the following variables on the server:

| Variable | Purpose |
| --- | --- |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | Railway PostgreSQL connection values; the API builds its JDBC URL from these |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Alternate database name, username and password variables used if the corresponding `PG*` variable is unset |
| `DATABASE_URL` | Optional full JDBC URL override |
| `AUDEX_SETUP_CODE` | One-time code for creating the first account; remove it after setup |
| `AUDEX_APP_URL` | Public web app origin used in password-reset links, e.g. `https://audex.mrhdigital.co.za` |
| `AUDEX_ALLOWED_ORIGINS` | Web app origin allowed to call the API |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | SMTP settings for account recovery emails |
| `AUDEX_MAIL_FROM` | Sender address for account emails |
| `BUCKET`, `ENDPOINT`, `REGION`, `ACCESS_KEY_ID`, `SECRET_ACCESS_KEY` | S3-compatible object storage for audit photos |

Railway's `RAILWAY_BUCKET_NAME` is a display name and `RAILWAY_BUCKET_ID` is an internal identifier; neither is the S3 bucket name. The API needs `BUCKET`, `ENDPOINT` and `ACCESS_KEY_ID` in addition to `REGION` and `SECRET_ACCESS_KEY` to enable photo storage. Leave `BUCKET` unset to run without photo storage. Without SMTP configuration, account recovery emails are only written to the application log. Do not commit server credentials or put them in the frontend repository.

## Current limitations

- The rate limiter is in-memory and is not shared across multiple server instances.
- The web app does not yet sync sites or training sessions to the API, or pull server data onto a second device.
- The web app dashboard currently uses labelled demo data until it is connected to server-backed audit data.
- Run `mvn test` and validate account setup, sign-in, audit upload, migrations and photo storage against a real PostgreSQL database and bucket before production use.

The plain-Java harnesses can be run without Maven from Bash:

```sh
D=src/main/java/za/co/mrhdigital/audex
javac -d /tmp/ah tools/AuthHarness.java $D/auth/{PasswordPolicy,Tokens,AttemptLimiter,Identity}.java
java -cp /tmp/ah AuthHarness
python3 tools/export_fixtures_flat.py shared /tmp/fx.flat
javac -d /tmp/jh tools/JavacHarness.java $D/scoring/{Result,RuleSet,ScoreResult,ScoredItem,ScoringEngine,Severity,Status}.java
java -cp /tmp/jh JavacHarness /tmp/fx.flat
```

---

### Developed by Mr. H Digital

![Mr. H Digital — Digital Solutions for Growing Businesses](brand/exports/logo/mrh-digital-logo-transparent.png)
