# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Working rules for AI agents are in `AGENTS.md` (loaded via `@AGENTS.md` below). The sections here cover build commands and architecture that require reading multiple files to understand.

@AGENTS.md

---

## Build & Test Commands

All commands run from the repo root with the Gradle wrapper.

**Per-commit routine (required before every commit):**

```bash
./gradlew spotlessApply          # apply Naver formatter — always run before git add
./gradlew compileJava            # verify main sources compile
./gradlew compileTestJava        # verify test sources compile
```

**When new files are added or heavily modified:**

```bash
./gradlew checkstyleMain checkstyleTest
# Windows: start build/reports/checkstyle/main.html
```

**Pre-push / pre-PR (required):**

```bash
./gradlew spotlessCheck          # CI runs this — must be green
./gradlew clean test             # full test run
```

**Run tests for a single service or test class:**

```bash
./gradlew :study-service:test --tests "com.backtoback.study.hint.*"
./gradlew :study-service:test --tests "com.backtoback.study.hint.service.HintQueryServiceTest"
```

Replace `:study-service:` with any service subproject (e.g. `:member-service:`, `:judge-service:`).

**Compile only one service:**

```bash
./gradlew :study-service:compileJava
```

---

## Architecture Overview

### Runtime topology

```
Next.js (Netlify) → HTTPS → Spring Cloud Gateway
                                   ↓ X-User-Id header (JWT validated at gateway)
         ┌──────────────────────────────────────────────────────┐
         │  member · problem/judge · contest · study · notification · integration │
         └──────────────────────────────────────────────────────┘
                   ↓ JPA/Flyway        ↓ Redis 7       ↓ SQS/SNS
              MySQL 8 (per-service schema)         Judge0 (isolated EC2)
```

- **Java 21 / Spring Boot 4.1**, one Gradle multi-project, each service its own subproject.
- Gateway issues a `Correlation-ID` header propagated through all HTTP calls and SQS messages.
- Services never share a DB schema; cross-service reads go through internal REST calls or events.

### Common modules (`com.backtoback.common`)

| Module | Key classes | Purpose |
| --- | --- | --- |
| `common-web` | `ApiResponse`, `GlobalExceptionHandler`, `GatewayUser` | Unified response envelope, exception → HTTP mapping, authenticated user from gateway header |
| `common-jpa` | `BaseTimeEntity` | `created_at` / `updated_at` via Spring Data Auditing (`LocalDateTime`) |
| `common-event` | Outbox relay, `ProcessedEvent` | Transactional Outbox + idempotent event consumption |

Do **not** reimplement these in a service's `global/` package.

### Per-service package layout

```
com.backtoback.{service}
├── {feature}/
│   ├── controller/   ← ResponseEntity<ApiResponse<T>>
│   ├── service/      ← @Transactional on public methods; @Transactional(readOnly=true) for reads
│   ├── repository/   ← Spring Data JPA
│   ├── domain/       ← @Getter + protected no-args ctor + static factory; no @Setter/@Builder
│   ├── dto/          ← record; static from(entity) for responses
│   ├── client/       ← outbound HTTP/SQS calls (outside any transaction)
│   └── config/       ← feature-scoped @ConfigurationProperties (use this, not @Value)
└── global/
    ├── config/       ← app-wide config (security, MVC, scheduling)
    └── error/        ← {Service}ErrorCode enum implements ErrorCode
```

Throw `BusinessException` (from `common-web`) with a `{Service}ErrorCode` value. Never catch exceptions in controllers to build error responses.

### Consistency model

- **Transactional Outbox**: state change + event row in the same DB transaction; relay publishes to SNS; consumers deduplicate via `processed_events`.
- **Idempotent consumption**: checked by event ID + revision comparison — consumers must not re-process.
- **judge-service**: general submissions use `judge_jobs` table with `SKIP LOCKED`; exam/contest submissions go through SQS.

### study-service specifics (owner D)

- `hint_requests` table has a virtual column `active_level` backing a unique key that prevents duplicate in-flight requests per member/problem/level.
- `HintDailyLimitService.DAILY_LIMIT = 20` is the authoritative limit constant (also used in error messages).
- `study.hint.feature.enabled` (default `true`) controls all `/api/v1/hint-requests/**` endpoints via a `HandlerInterceptor`.
- Error codes live in `StudyErrorCode`; the canonical list is `docs/dev/specifications/error-codes.md`.

### Key design constraints

- `@Transactional` only on `public` methods; not on the class. Write methods that intentionally skip a transaction must have a comment explaining why.
- Current time via injected `Clock`, not `LocalDateTime.now()`.
- Config values via `@ConfigurationProperties` record + `@Validated`, never `@Value`.
- Tests: unit tests use `MockitoExtension` + BDDMockito; controller tests use `@WebMvcTest` + `@MockitoBean`; DB tests use Testcontainers with `@Testcontainers(disabledWithoutDocker = true)`.
- `@DisplayName` on every test method in Korean describing condition → result.
