# audex-api

The AudEx server: Spring Boot 3.5 / Java 21 / Maven, Flyway + Postgres, deployed on Railway. The web app is the separate **audex-web** repository.
Keep this repository **private**.

```
src/main/java/.../scoring   ScoringEngine (integer half-point maths) + rule sets + checklist catalogue
src/main/java/.../auth      accounts, sign-in, recovery, password reset, bearer-token interceptor
src/main/java/.../api       audit sync, photo upload, scoring endpoint, CORS
src/main/java/.../storage   photo storage (S3-compatible Railway bucket)
src/main/resources/db/migration   V1 schema, V2 training sessions, V3 supervisor, V4 accounts
shared/    template.json, rulesets.json, scoring-fixtures.json  <- COPY, see below
tools/     javac harnesses (run without Maven), export_fixtures_flat.py, generate_seed.py (rebuilds shared/ from the WPC workbook)
```

## Run
```
mvn test
mvn spring-boot:run        # needs Postgres: DATABASE_URL / PGUSER / PGPASSWORD
```

## The shared/ folder is a copy
`audex-web` has the identical `shared/` folder. When the checklist or scoring rules change, change **both** copies. Both repositories test the
same `scoring-fixtures.json`, so a copy that drifts makes tests fail.
Rebuild from the workbook: `python3 tools/generate_seed.py <WPC_Health_Care_Housekeeping_Audit_Aligned.xlsx> shared`

## Deploy on Railway
Create a service from this repository (Railway detects Maven). Variables:

| Variable | Purpose |
|---|---|
| `DATABASE_URL`, `PGUSER`, `PGPASSWORD` | Postgres (use Railway's Postgres plugin values; `DATABASE_URL` must be a `jdbc:postgresql://...` URL) |
| `AUDEX_SETUP_CODE` | Needed once to create the first account. Remove it afterwards. |
| `AUDEX_APP_URL` | Address of the web app, used in reset links, e.g. `https://audex.mrhdigital.co.za` (no trailing slash) |
| `AUDEX_ALLOWED_ORIGINS` | Same address, so the browser may call the API |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | Outgoing email (SMTP). Without them emails are only written to the log, so username recovery and password reset will not reach anyone. |
| `AUDEX_MAIL_FROM` | The "from" address |
| `BUCKET`, `ENDPOINT`, `REGION`, `ACCESS_KEY_ID`, `SECRET_ACCESS_KEY` | Railway storage bucket for photos (leave `BUCKET` unset to run without photo storage) |

Never commit a `.env` file or any key.

## Rule sets
| Code | Status | What it does |
| --- | --- | --- |
| `WPC_V1` | **Active (default)** | The current WPC sheet: 95% / 85% thresholds, Critical Non-Compliant override |
| `WPC_V2_PROPOSED` | Pending approval | V1 plus four caps: Critical Partial, 3+ Major failures, high-risk section under 85%, N/A over 10% flags review |

Every audit stores the rule set it was scored under. Thresholds are integer tenths of a percent and points are half-points, so Java and TypeScript agree exactly.
The server re-scores every audit it receives and never trusts the phone's figure.

## API
- `PUT /api/audits/{id}`: idempotent audit sync (client-made id), requires a reason for every N/A, auditor taken from the signed-in account
- `PUT /api/audits/{id}/photos/{photoId}`: photo upload (after its audit has synced)
- `POST /api/auth/setup | login | logout | logout-all | forgot-username | forgot-password | reset-password`, `GET /api/auth/status`
- `GET/PATCH /api/me`, `POST /api/me/password`
- Everything under `/api` except `/api/auth/**` needs `Authorization: Bearer <token>`

## Sign-in design
- Passwords: BCrypt (cost 12), 10 to 128 characters, no composition rules, rejects common passwords, repeated characters, and passwords containing the username or email name.
- Tokens: random 256-bit, only a SHA-256 hash is stored. Sessions last 30 days of use, 90 days at most. Reset tokens work once and last 30 minutes; a new request cancels older ones; a reset signs out every device and emails a notice.
- Unknown usernames take the same time as wrong passwords. Limits: 5 failures per 15 min per username, 30 per 15 min per address, 3 recovery requests per hour, 5 password confirmations per 15 min. (Held in memory: fine for one server instance; move to the database before running several.)
- Forgot-username and forgot-password answer identically whether or not the account exists.

## Testing status
The Java scoring engine passes `tools/JavacHarness.java` and the auth rules pass `tools/AuthHarness.java` (both run with plain `javac`):
```
D=src/main/java/za/co/mrhdigital/audex
javac -d /tmp/ah tools/AuthHarness.java $D/auth/{PasswordPolicy,Tokens,AttemptLimiter,Identity}.java && java -cp /tmp/ah AuthHarness
python3 tools/export_fixtures_flat.py shared /tmp/fx.flat
javac -d /tmp/jh tools/JavacHarness.java $D/scoring/{Result,RuleSet,ScoreResult,ScoredItem,ScoringEngine,Severity,Status}.java && java -cp /tmp/jh JavacHarness /tmp/fx.flat
```
The Spring controllers, SQL migrations and photo storage have **not been run** (Maven Central was unreachable where they were written). Run `mvn test`, then do one real
setup + sign-in + audit upload against a Postgres database before relying on this.
