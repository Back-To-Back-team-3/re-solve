# 스케줄러 v1.0

> 최종 수정일: 2026.10.07(수)
> 

---

## 0. 공통 규칙·공통 스케줄러

> 작성: 담당 A · 공통 모듈 `common-scheduling`, `common-event` (기획서 T1-12)
> 

### 0.1 공통 규칙

| 항목 | 규칙 |
| --- | --- |
| 시각 | 서비스·DB 시간대 `Asia/Seoul`, 컬럼 `DATETIME(6)`. 비교는 애플리케이션 `Clock` 주입값으로 해 테스트에서 시각을 고정한다. cron은 `zone = "Asia/Seoul"` 명시 |
| 실행 방식 | 상태 전이·대사는 `fixedDelay`(이전 실행 종료 후 대기). 정리 배치만 cron |
| ShedLock | JDBC 공급자, 서비스 스키마의 `shedlock` 테이블. `lockAtMostFor` = 정상 최장 실행 시간의 2배 이상, 주기보다 짧게. `lockAtLeastFor`는 기본 0 (노드 간 시계 차이로 두 번 도는 것은 조건부 UPDATE가 흡수) |
| 트랜잭션 경계 | 대상 1건당 1트랜잭션. 목록 조회는 잠금 없이, 처리 시 조건부 UPDATE로 재검증. 한 건 실패가 회차 전체를 롤백하지 않는다 |
| 처리량 상한 | 한 회차 대상 수 상한(페이지)과 실행 시간 상한을 두고, 남은 대상은 다음 회차가 시각 조건으로 다시 고른다 |
| 원격 호출 | DB 행 잠금을 잡은 채 원격 호출 금지. 조회·호출 후 새 트랜잭션에서 상태 재검증 |
| 이벤트 | 상태 변경과 같은 트랜잭션에 Outbox 기록. 스케줄러 발행 이벤트의 `correlationId`는 `sched-{작업명}-{UUID}` |
| 재기동 | 진행 상태를 메모리에 두지 않는다. 모든 대상은 DB 조건(시각·상태)으로 재선정 |
| 지표 | Micrometer `scheduler.run.duration`, `scheduler.items{result}`, `scheduler.backlog.oldest.seconds` (작업명 태그) |
| 장애 분리 | 알림·캐시·외부 API 실패가 상태 전이 트랜잭션을 롤백시키지 않는다(별도 스케줄러·커밋 후 처리) |
| 환경 중지 | 중지 절차(정책 §9.4)에서 신규 접수 차단 후, 전이·채점 스케줄러는 미종결 0건 확인까지 계속 실행 |

### 0.2 공통 스케줄러

#### 0.2.1 `OutboxRelay` — 이벤트 발행

| 항목 | 값 |
| --- | --- |
| 소유 | Outbox를 쓰는 6개 서비스(member·problem·judge·contest·study·integration) / 공통 모듈 |
| 실행 방식 | `fixedDelay = 1초`, `initialDelay = 5초` |
| 잠금 | ShedLock 없음. `SELECT … WHERE status = 'PENDING' ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED` |
| 트랜잭션 경계 | 선점 트랜잭션 안에서 건별 발행 → `PUBLISHED`. 발행 실패 건은 `retry_count + 1` 후 같은 트랜잭션 커밋 |
| 대상 조회 | `idx_outbox_events_status_created_at` |
| 상태 전이 | `PENDING → PUBLISHED`, `retry_count > 5`면 `PENDING → FAILED` |
| 발행 | SNS Publish(도메인 이벤트) 또는 SQS SendMessage(채점 요청). 메시지 속성 `eventType`·`schemaVersion` |
| 후처리 | contest `Exam/ContestSubmissionRequested` 발행 성공 시 같은 트랜잭션에서 접수 `ACCEPTED → REQUESTED` (먼저 온 `JUDGED`·`FAILED` 보존). member 진단 제출 동일 |
| 지표 | 발행 지연(`published_at − created_at`) p95 ≤ 2초(정책 §11), `PENDING` 최장 경과, `FAILED` 건수 |

**동시성 설계 포인트:**

- 여러 인스턴스가 `SKIP LOCKED`로 서로 다른 행을 가져가므로 같은 행 중복 발행이 없다. 발행 후 커밋 전 장애 시 재발행은 같은 `eventId`라 소비자가 흡수한다.
- SNS 호출 중 행 잠금을 쥐지만 배치 100건·호출 타임아웃 2초로 상한을 둔다. 잠금 시간이 길어지면 배치를 줄인다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 다른 인스턴스 Relay | 같은 PENDING 행 | 한 인스턴스만 선점 | `SKIP LOCKED` |
| 업무 트랜잭션 | 커밋 전 Outbox 행 | 보이지 않음, 커밋 후 다음 회차 | 트랜잭션 격리 |
| contest 결과 소비 | `REQUESTED` 전이 전에 `JUDGED` 도착 | `JUDGED` 유지 | `WHERE status = 'ACCEPTED'` 조건부 UPDATE |

**재기동·지연 시:** 미발행 행은 그대로 남아 재기동 후 첫 회차에 발행된다. SNS 장애가 1분 이상이면 Outbox 적체 알림(정책 §11).

#### 0.2.2 `OutboxCleanupScheduler` — 발행 완료 이벤트 정리

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `cron = 0 0 2 * * *` (Asia/Seoul) |
| 잠금 | ShedLock `OutboxCleanupScheduler`, `lockAtMostFor = 30분` |
| 처리 | `DELETE FROM outbox_events WHERE status = 'PUBLISHED' AND published_at < now() - INTERVAL 7 DAY LIMIT 1000` 반복 |
| 제외 | `PENDING`·`FAILED`는 기간과 무관하게 보존 |
| 경합 | Relay와 대상 집합이 겹치지 않음(`PUBLISHED`만) |

#### 0.2.3 `ProcessedEventCleanupScheduler` — 소비 기록 정리

| 항목 | 값 |
| --- | --- |
| 소유 | 이벤트를 소비하는 7개 서비스 |
| 실행 방식 | `cron = 0 10 2 * * *` |
| 잠금 | ShedLock `ProcessedEventCleanupScheduler`, `lockAtMostFor = 30분` |
| 처리 | `processed_at < now() - INTERVAL 14 DAY` 1,000건 단위 반복 삭제 (`idx_processed_events_processed_at`) |
| 경합 | 14일 = SQS 최대 보관 기간이라 삭제된 `eventId`의 메시지는 큐에 남아 있을 수 없다. DLQ redrive는 이벤트 계약서 §7.3 절차로 순서 값 확인 |
- 감사 로그(`audit_logs`)는 정리하지 않는다. 이미지·알림·차단 복제본 정리는 각 서비스 절에 둔다.

---

## 1. 인증·회원 (member)

> 작성: 담당 E · 서비스: `member-service`
> 
- `DiagnosisExpiryScheduler` — 5초, 제한 시각 도달 진단 종료 + Draft 자동 제출
- `DiagnosisScoringScheduler` — 1분, 채점 종결 진단 `SCORED` 보정
- `DiagnosisCooldownReleaseScheduler` — 10분, 쿨타임 해제 알림

> Refresh Token·로그인 교환 코드·OAuth `state`·Access 차단 목록은 Redis TTL로 만료되므로 스케줄러가 없다.
> 

### 1.1 `DiagnosisExpiryScheduler` — 진단 만료 종료

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `member-service` / `diagnosis` |
| 실행 방식 | `fixedDelay = 5초`, `initialDelay = 5초` |
| 잠금 | ShedLock `DiagnosisExpiryScheduler`, `lockAtMostFor = 30초` |
| 한 회차 처리량 | 최대 100건, 20초 뒤 새 선정 중단 |
| 트랜잭션 경계 | 응시 1건당 1트랜잭션 (종료 + 자동 제출 + Outbox 원자) |
| 대상 조회 | `status = 'IN_PROGRESS' AND ends_at + 3초 <= now()` / `idx_diagnosis_attempts_status_ends_at` |
| 상태 전이 | `diagnosis_attempts`: `IN_PROGRESS → CLOSED`(`close_reason = EXPIRED`, `closed_at = ends_at`) |
| 발행 이벤트 | 자동 제출 문항마다 `DiagnosisSubmissionRequested`(`submissionType = AUTO`, `receivedAt = ends_at`) |
| 지표 | 종료 건수, 자동 제출 건수, `ends_at` 대비 처리 지연 |

**동작:**

1. `UPDATE diagnosis_attempts SET status='CLOSED', close_reason='EXPIRED', closed_at=ends_at WHERE id=:id AND status='IN_PROGRESS'`. 0행이면 수동 종료가 먼저 끝난 것이므로 종료.
2. 문항별로 언어 무관 최대 `seq` Draft를 고른다. 마지막 제출 `draft_seq`보다 크고 공백만이 아닌 코드면 `diagnosis_submissions`(`AUTO`, `ACCEPTED`)를 만든다.
3. 자동 제출이 0건이고 모든 제출이 이미 종결이면 같은 트랜잭션에서 채점 확정(1.2 동작)까지 진행한다.

**동시성 설계 포인트:**

- 수동 종료 API(1.5.6)와 같은 조건부 UPDATE를 쓰므로 둘 중 하나만 종료한다(API 1.5.6).
- `uk_diagnosis_submissions_attempt_problem_seq`로 직접 제출과 자동 제출이 같은 `seq`를 두 번 만들지 않는다.
- +3초는 처리 유예이며 접수 기준은 `ends_at` 그대로다. 유예 중 도착한 저장·제출은 접수 시각 `> ends_at`이라 `DIAGNOSIS_CLOSED`.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 최종 제출 API | 만료와 동시 종료 | 먼저 커밋한 쪽만 종료, 자동 제출도 그쪽이 1회 | `WHERE status = 'IN_PROGRESS'` |
| 문항 제출 API | 마감 직전 같은 `seq` 제출 | 1건만 생성 | 유일 제약 |
| Draft 저장 API | 유예 3초 중 저장 | 거절 | 접수 시각 `<= ends_at` 검증 |

**재기동·지연 시:** 시각 조건으로 밀린 응시를 처리한다. `closed_at`은 실행 시각이 아니라 `ends_at`으로 기록해 쿨타임 계산이 지연에 영향받지 않는다.

**명세서와의 관계:** 정책 §8.3 진단 종료 / API 1.5.6 / ERD 4.1.6·4.1.9, 6.1

### 1.2 `DiagnosisScoringScheduler` — 진단 채점 확정 보정

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `fixedDelay = 1분`, `initialDelay = 1분` |
| 잠금 | ShedLock `DiagnosisScoringScheduler`, `lockAtMostFor = 50초` |
| 대상 조회 | `status = 'CLOSED'` 이고 연결된 `diagnosis_submissions`가 모두 `JUDGED` 또는 `FAILED` |
| 상태 전이 | `CLOSED → SCORED`, `score`·`result_level`·`scored_at`. `FAILED`가 1건이라도 있으면 `is_cooldown_exempt = true` |
| 발행 이벤트 | `TagLevelChanged`(`DIAGNOSIS`), `LearningProfileUpdated` |

**동작:** 기본 경로는 채점 결과 이벤트(이벤트 계약서 J5) 소비 트랜잭션이 마지막 제출 종결을 감지해 바로 `SCORED`로 전이하는 것이다. 이 스케줄러는 소비 경로가 놓친 응시(이벤트 순서·DLQ 재처리 후)를 보정한다. 레벨은 `max(현재 레벨, 진단 결과)`로 하향하지 않는다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 결과 이벤트 소비 | 같은 응시를 동시에 확정 | 한쪽만 전이 | `WHERE status = 'CLOSED'` |
| 늦은 재채점 결과 | `SCORED` 이후 도착 | 레벨 재계산 대상 아님, 기록만 (진단 재채점 적용 여부 5주차 확정) | 정책 미결정 |

**재기동·지연 시:** `FAILED` 제출이 남은 응시는 확정하되 쿨타임을 면제한다. 미종결(`REQUESTED`) 제출이 30분 넘게 남으면 운영 알림.

### 1.3 `DiagnosisCooldownReleaseScheduler` — 쿨타임 해제 알림

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `fixedDelay = 10분` |
| 잠금 | ShedLock `DiagnosisCooldownReleaseScheduler`, `lockAtMostFor = 8분` |
| 대상 조회 | 태그별 마지막 응시가 `SCORED`·쿨타임 대상이고 `closed_at + 3일 <= now()`, 레벨 `LV1`·`LV2`, 해제 알림 미발송 |
| 상태 변경 | `diagnosis_attempts.cooldown_notified_at = now()` (ERD 추가 컬럼 제안) 조건부 UPDATE |
| 발행 이벤트 | `TagLevelChanged`(`reasonType = COOLDOWN_RELEASED`) |
- 응시 가능 여부 판정은 API가 시각으로 직접 하므로 이 스케줄러가 늦어도 응시는 막히지 않는다. 알림만 늦는다.
- 해제 전 같은 태그에 새 응시가 시작되면 대상에서 빠진다(마지막 응시 기준).

**명세서와의 관계:** 정책 §8.3 레벨 알림(쿨타임 해제) / ERD 4.1.6 (`cooldown_notified_at` 추가 필요)

---

## 2. 문제 (problem)

> 작성: 담당 B · 서비스: `problem-service`
> 
- `UnlinkedAssetCleanupScheduler` — 매일 04:30, 본문에 연결되지 않은 이미지·DB 실패로 남은 업로드 파일 정리

> 문제 공개·회차 전이는 관리자 API가 즉시 수행하므로 상태 전이 스케줄러는 없다. 검수 워크플로(P2, 보류)를 도입하면 심사 만료 스케줄러를 추가한다.
> 

### 2.1 `UnlinkedAssetCleanupScheduler` — 미연결 파일 정리

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `problem-service` / `asset` |
| 실행 방식 | `cron = 0 30 4 * * *` |
| 잠금 | ShedLock `UnlinkedAssetCleanupScheduler`, `lockAtMostFor = 30분` |
| 한 회차 처리량 | 500건 |
| 대상 조회 | `problem_images.status = 'PENDING' AND created_at < now() - :grace`, `problem_body_assets`·`problem_test_assets`의 `PENDING`(등록 트랜잭션 실패분) |
| 처리 | S3 객체 삭제 → 성공한 행만 DB 삭제 (1건 1트랜잭션) |
| 대기 시간 `:grace` | 확정 필요 (정책 미결정, 2주차). 초안 24시간 |
| 지표 | 삭제 건수, S3 삭제 실패 건수 |

**동시성 설계 포인트:**

- 삭제 직전 `UPDATE … SET status='DELETING' WHERE id=:id AND status='PENDING'`로 선점한다. 0행이면 그사이 본문에 연결(`LINKED`)된 것이므로 건너뛴다.
- 회차 경로는 불변이므로 `LINKED` 파일은 어떤 경우에도 삭제 대상이 아니다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 문제 등록·새 회차 API | 정리 중 이미지 연결 | 연결 우선, 정리 건너뜀 | `status = 'PENDING'` 조건부 UPDATE |
| S3 삭제 실패 | 네트워크 오류 | `DELETING`에서 `PENDING`으로 복귀, 다음 날 재시도 | 행 삭제는 S3 성공 후 |

**명세서와의 관계:** 정책 §4 이미지 정리 / API 2.3.1·2.4.1 / ERD 6.2 미연결 이미지 정리

---

## 3. 제출·채점 (judge)

> 작성: 담당 B · 서비스: `judge-service`
> 
- `JudgeJobExecutor` — 1초(유휴 시 최대 5초), `judge_jobs` 선점·실행 (워커)
- `JudgeLeaseRecoveryScheduler` — 30초, 임대 만료·`RUNNING` 10분 초과 회수
- `JudgeTokenRecoveryScheduler` — 30초, Judge0 token 응답 유실(`UNKNOWN`) 복구
- `JudgeReconciliationScheduler` — 5분, `submissions` ↔ `judge_jobs` 상태 대사
- `RunLockReconciliationScheduler` — 1분, 비정상 종료된 `run:{memberId}` 락 해제
- `JudgeBacklogMonitorScheduler` — 1분, 적체·서킷 상태 지표

### 3.0 상태 대응 (정책 §10)

| `submissions.status` | `judge_jobs.status` (최신 회차) | 정상 여부 |
| --- | --- | --- |
| `QUEUED` | `PENDING` 또는 `RETRY_WAITING` | 정상 |
| `JUDGING` | `RUNNING` (임대 유효) | 정상 |
| `COMPLETED` | `SUCCEEDED` | 정상 |
| `FAILED` | `FAILED` | 정상 |
| 그 외 조합 | — | 대사 대상 (3.4) |

### 3.1 `JudgeJobExecutor` — 채점 작업 선점·실행

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `judge-service` / `executor` |
| 실행 방식 | 폴링 루프. 대상 있으면 1초, 없으면 1→2→5초 백오프 (정책 §2.1) |
| 잠금 | ShedLock 없음. `SELECT … WHERE status IN ('PENDING','RETRY_WAITING') AND next_run_at <= now() ORDER BY next_run_at LIMIT :slots FOR UPDATE SKIP LOCKED` |
| 동시 처리 | 인스턴스당 `slots` = 2 (부하 측정에서 2 → 4 → 8 비교, 정책 §11) |
| 트랜잭션 경계 | ① 선점 Tx: `RUNNING`·`lease_token` 신규·`lease_until = now + 120초`·`try_count`는 그대로, 제출 `JUDGING` ② Tx 밖: 채점 데이터 조회·Judge0 실행·폴링 ③ 결과 Tx: 조건부 UPDATE + 제출 반영 + Outbox |
| 임대 연장 | 실행 중 30초마다 `UPDATE … SET lease_until = now + 120초 WHERE id = :id AND status = 'RUNNING' AND lease_token = :token`. 0행이면 즉시 실행 중단 |
| 상태 전이 | 성공: 작업 `SUCCEEDED`, 제출 `COMPLETED`(+판정). `SYSTEM_ERROR`: `try_count + 1`, `RETRY_WAITING`·`next_run_at = now + 10초/30초`, 제출 `QUEUED`. `try_count > 3`: 작업·제출 `FAILED` |
| 발행 이벤트 | 문맥별 `SubmissionJudged` / `ExamSubmissionJudged` / `ContestSubmissionJudged`(+진단 확정 필요), 실패 시 `SubmissionFailed` |
| 지표 | 대기→선점 p95(평시 ≤ 10초, 마감 폭주 ≤ 60초), 실행 시간, 시도 결과별 건수 |

**동작:**

1. 선점 후 `judge_job_runs`에 시도 1행을 만들고 고정 회차 채점 데이터(2.6.2)를 조회한다. 조회 실패는 `SYSTEM_ERROR` 처리.
2. 테스트별로 `judge_test_results.token_status`를 보고 `NOT_CREATED`면 Judge0 실행 생성, `CREATED`면 기존 token으로 폴링만 한다(조회 실패로 새 실행 생성 금지, T2-09-03). 생성 응답이 유실되면 `UNKNOWN`으로 기록하고 3.3이 복구한다.
3. 전체 테스트 종결 후 판정 정규화(MLE 메모리 보정 포함) → 결과 Tx에서 `WHERE status = 'RUNNING' AND lease_token = :token`으로 저장. 0행이면 결과를 버린다.
4. Judge0 서킷이 열리면 새 선점을 멈춘다(정책 §3.4). 실행 중 작업은 임대 만료로 회수되도록 둔다.

**동시성 설계 포인트:**

- 같은 작업을 두 실행기가 동시에 가질 수 없다(`SKIP LOCKED` + 선점 시 새 `lease_token`). 회수 후 이전 실행기의 늦은 결과는 토큰 불일치로 폐기된다.
- 재채점은 `(submission_id, judge_attempt)` 새 행이라 이전 회차 작업과 섞이지 않는다. 제출에는 최신 회차 결과만 반영한다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 다른 실행기 | 같은 PENDING 작업 | 하나만 선점 | `SKIP LOCKED` |
| 임대 회수 스케줄러 | 실행 중 임대 만료 회수 후 결과 도착 | 늦은 결과 폐기, 새 시도가 실행 | `lease_token` 조건 |
| 관리자 재채점 | 회차 실행 중 재채점 요청 | `SUBMISSION_JUDGING_IN_PROGRESS`(409) | 미종결 회차 확인 |
| 관리자 작업 복구 API | 같은 작업 동시 처리 | 먼저 커밋한 쪽만 | 상태 조건부 UPDATE |

**재기동·지연 시:** 메모리 큐가 없으므로 재기동 후 DB 조건으로 다시 선점한다. 실행 중이던 작업은 3.2가 회수한다(정책 §11 "10분 내 종결 경로 복귀").

**명세서와의 관계:** 정책 §3.2·§3.4 / 기획서 T2-06·T2-09 / ERD 4.3.2~4.3.4, 6.3

### 3.2 `JudgeLeaseRecoveryScheduler` — 임대 만료·장시간 작업 회수

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `fixedDelay = 30초` |
| 잠금 | ShedLock `JudgeLeaseRecoveryScheduler`, `lockAtMostFor = 25초` |
| 대상 조회 | ① `status = 'RUNNING' AND lease_until < now()` ② `status = 'RUNNING' AND started_at < now() - 10분` (`deadline_at` 초과 포함) / `idx_judge_jobs_status_lease_until` |
| 상태 전이 | `RUNNING → RETRY_WAITING`(`try_count + 1`, `lease_token = NULL`, `judge_job_runs.result = LEASE_EXPIRED`), 제출 `QUEUED`. `try_count > 3` 또는 `deadline_at` 경과면 `FAILED` + `SubmissionFailed` |
- 회수 UPDATE는 `WHERE id = :id AND status = 'RUNNING' AND lease_token = :observedToken`. 그사이 결과가 저장됐거나 연장됐으면 0행으로 건너뛴다.
- 회수도 `try_count`에 포함한다(정책 §3.4). 종결된 회차는 되살리지 않는다(T2-09-04).

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 실행기 결과 저장 | 회수와 결과 저장 동시 | 먼저 커밋한 쪽 | 둘 다 `lease_token` 조건 |
| 실행기 임대 연장 | 연장 직전 회수 | 회수 성공 시 연장 0행 → 실행기 중단 | 동일 |

### 3.3 `JudgeTokenRecoveryScheduler` — Judge0 응답 유실 복구

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `fixedDelay = 30초` |
| 잠금 | ShedLock `JudgeTokenRecoveryScheduler`, `lockAtMostFor = 25초` |
| 대상 조회 | `token_status = 'UNKNOWN' AND updated_at < now() - 30초` / `idx_judge_test_results_token_status` |
| 처리 | 작업이 이미 회수·종결됐으면 행만 정리. 진행 중이면 `NOT_CREATED`로 되돌려 실행기가 다음 시도에서 재생성. 같은 시도 안 재생성 2회 초과면 해당 시도 `SYSTEM_ERROR` |
- Judge0 생성 여부를 단정하지 않고 대기·재시도 한도로 복구한다(T2-09-02). 중복 생성된 Judge0 실행 결과는 token이 기록되지 않아 반영되지 않는다.

### 3.4 `JudgeReconciliationScheduler` — 제출·작업 상태 대사

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `fixedDelay = 5분`, `initialDelay = 5분` |
| 잠금 | ShedLock `JudgeReconciliationScheduler`, `lockAtMostFor = 4분` |
| 대상 조회 | `submissions.status IN ('QUEUED','JUDGING') AND updated_at < now() - 2분`과 최신 회차 작업 조인 / `idx_submissions_status_updated_at` |

| 불일치 | 원인 추정 | 조치 |
| --- | --- | --- |
| 제출 `QUEUED`, 작업 없음 | 접수 Tx 버그 | 기록·운영 알림 (자동 생성 금지) |
| 제출 `JUDGING`, 작업 `PENDING`/`RETRY_WAITING` | 회수 후 제출 반영 누락 | 제출 `QUEUED` 정정 |
| 제출 `QUEUED`/`JUDGING`, 작업 `SUCCEEDED` | 결과 Tx 부분 실패 | 작업 결과로 제출 `COMPLETED` 보정 + 결과 이벤트 Outbox(같은 회차, 소비자 멱등) |
| 제출 `QUEUED`/`JUDGING`, 작업 `FAILED` | 동일 | 제출 `FAILED` + `SubmissionFailed` |
| 작업 `RUNNING` 임대 만료 | 회수 지연 | 3.2에 위임 |
- 모든 보정은 제출 행 `WHERE status = :observed AND judge_attempt = :attempt` 조건부 UPDATE. 정상 경로가 먼저 끝났으면 0행.

### 3.5 `RunLockReconciliationScheduler` — Run 락 정리

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `fixedDelay = 1분` |
| 잠금 | ShedLock `RunLockReconciliationScheduler`, `lockAtMostFor = 50초` |
| 대상 | Redis `run:{memberId}` 키 (값 = 소유 토큰·인스턴스 ID·시작 시각) 중 시작 후 40초 초과 |
| 처리 | 소유 인스턴스의 실행 레지스트리(`run:active:{instanceId}`, 하트비트 10초)에 토큰이 없으면 Lua `compare-and-delete`로 해제 |
- Run에 TTL 쿨다운을 두지 않는다는 정책(§3.2)을 지키면서, 인스턴스 비정상 종료로 영원히 `RUN_IN_PROGRESS`가 되는 것을 막는다. 40초 = 실행 기한 30초 + 중단 처리 여유.
- 정상 경로 해제와 경합해도 소유 토큰 비교 삭제라 다른 실행의 락을 지우지 않는다.

### 3.6 `JudgeBacklogMonitorScheduler` — 적체 지표

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `fixedDelay = 1분` (ShedLock, 1대) |
| 처리 | 상태별 작업 수, 가장 오래된 `PENDING` 대기 시간, 서킷 상태를 게이지로 기록. 상태 변경 없음 |
| 알림 | 채점 대기 60초 초과 2분 지속, `FAILED` 신규 발생 (정책 §11) — Grafana 경보 규칙 |

> SQS `exam-submission-queue` 소비는 스케줄러가 아니라 리스너다. 소비 Tx 규칙은 이벤트 계약서 C1·C2.
> 

---

## 4. 모의 코테·대회 (contest)

> 작성: 담당 C · 서비스: `contest-service`
> 

**검토 메모 (v1.0 통합):** API §4·부록 B와 대조해 불일치가 없어 본문은 작성본을 유지했다. 이벤트명·필드는 이벤트 계약서 C1~C15, 결과 소비 조건은 J2·J3을 따른다. ERD 미반영 테이블(`result_snapshots`, `contest_admin_operations`, `contest_resource_guards`, `contest_participant_histories`)은 ERD 역반영 대상이다(API 부록 D).

- `ExamLifecycleScheduler` — 5초 주기, 시험 시작·종료
- `ExamAutoSubmissionScheduler` — 5초 주기, 개인 마감 Draft 자동 제출
- `ExamResultFinalizationScheduler` — 10초 주기, 시험 확정
- `ContestLifecycleScheduler` — 5초 주기, 대회 시작·종료
- `ContestResultFinalizationScheduler` — 10초 주기, 대회 확정
- `ContestSubmissionReconciliationScheduler` — 5분 주기, 접수·채점 대사
- `ContestOperationReconciliationScheduler` — 5분 주기, 관리자·자원 준비 복구
- `ContestCacheRebuildScheduler` — 5분 주기, 순위 캐시 복구

### 4.0 실행·잠금·복구 규칙

| 항목 | 값 |
| --- | --- |
| 시각 | UTC 서버 시각은 Asia/Seoul로 변환해 DB의 DATETIME(6) 값과 비교하고, API는 +09:00 오프셋으로 직렬화한다. 신규 접수는 참가자 잠금 획득 직후의 서버 시각이 개인/전체 마감 이하일 때만 허용한다. 상태 전이 지연은 접수 시간을 늘리지 않는다. |
| 실행·ShedLock | 각 작업 fixedDelay, initialDelay는 해당 주기. 같은 작업명 DB ShedLock, lockAtLeastFor=0, lockAtMostFor=30초 |
| 처리량 | 시각·ID 오름차순, 페이지당 100건. 한 실행 20초 뒤 새 대상 선정 중단. 실패 대상은 롤백·다른 대상 계속, 다음 주기에 DB 조건으로 재선정 |
| 잠금 순서 | 필요 자원 가드(resource_type·resource_id 순) → 시험·대회 → 참가자 → 제출·결과. 종료·자동 제출·집계는 PREPARED 가드를 이유로 보류하지 않음 |
| 원격 호출 | DB 행 잠금 없이 조회·호출. 새 트랜잭션에서 상태·회차·operationId 재검증. 불명확한 원격 성공은 무효 처리·확정 금지 |
| Outbox | DB 변경과 같은 트랜잭션. 공통 Relay 발행. 발행 완료는 ACCEPTED만 REQUESTED로 전이하며 먼저 온 JUDGED/FAILED 보존 |
| 지표·운영 알림 | 선정·성공·보류·실패·실행 시간·최장 미종결/원격 경과 기록. 미해결 FAILED 및 10분 이상 접수·작업·가드는 운영 알림. 사용자 확정 알림과 구분 |

### 4.1 `ExamLifecycleScheduler` — 시험 시작·종료

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `contest-service` / `exam` |
| 실행 방식 | `fixedDelay = 5초`, `initialDelay = 5초` |
| 잠금 | ShedLock `ExamLifecycleScheduler`. §4.0 임대·행 잠금 순서 적용 |
| 한 회차 처리량 | 페이지당 100건, 20초 뒤 새 선정 중단. 남은 대상은 다음 회차 |
| 트랜잭션 경계 | 시험 1건당 1트랜잭션 |
| 대상 조회 | `SCHEDULED AND starts_at <= now` 또는 `IN_PROGRESS AND ends_at < now` |
| 상태 전이 | SCHEDULED → IN_PROGRESS → CLOSED. 미입장 활성 REGISTERED → ABSENT |
| 발행 이벤트 | ExamStarted / ExamClosed |
| 지표 | §4.0 공통 지표 및 대상 상태·회차·버전별 보류 원인 |

**동작:**

1. 부모 잠금 뒤 최신 상태·설정을 다시 확인한다. 시작에 도달하고 마감 전이면 IN_PROGRESS, 전체 마감을 초과하면 CLOSED로 전이하고 해당 Outbox를 저장한다.
2. 전체 마감까지 입장하지 않은 활성 REGISTERED는 ABSENT·이력을 저장한다. CANCELED는 유지한다. 입장자의 FINISHED·자동 제출은 §4.2에서 처리한다.
3. 스터디 종료 준비로 취소된 예정 시험은 시작시키지 않는다. 진행 시험은 원래 마감까지 진행하고 CLOSED 뒤 채점·확정을 계속한다.
4. 개인 FINISHED를 부모 CLOSED 조건으로 사용하지 않는다. 종료·잠정·개인 완료 사용자 알림을 발행하지 않는다.

**동시성 설계 포인트:**

- 시작·종료 조건을 행 잠금 안에서 재확인하며 같은 상태 전이는 한 번만 저장한다.
- CLOSED가 되면 스터디 prepare의 ready 재조회가 성공할 수 있다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 수정·취소 API | 시작 직전 설정 변경 | 먼저 커밋한 상태·설정만 반영 | 부모 잠금·서버 시각 재검증 |
| 입장 API | 전체 마감 직전 입장 | 유효 입장은 유지, 미입장만 ABSENT | 부모 → 참가자 잠금 |

**재기동·지연 시:** 시작·종료가 모두 지난 SCHEDULED는 같은 트랜잭션에 두 전이 이력을 남기고 CLOSED까지 처리한다. 지난 시작 알림을 새로 예약하지 않는다. 소비자는 설정 버전·시각으로 만료 예약을 제거한다.

**명세서와의 관계:** API §4.1·§4.2 / DB §4.4.1·§4.4.3 / 정책 §5

### 4.2 `ExamAutoSubmissionScheduler` — 개인 마감 Draft 자동 제출

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `contest-service` / `exam` |
| 실행 방식 | `fixedDelay = 5초`, `initialDelay = 5초` |
| 잠금 | ShedLock `ExamAutoSubmissionScheduler`. §4.0 임대·행 잠금 순서 적용 |
| 한 회차 처리량 | 페이지당 100건, 20초 뒤 새 선정 중단. 남은 대상은 다음 회차 |
| 트랜잭션 경계 | 참가자 1명당 1트랜잭션. AUTO·Outbox·완료 표식·FINISHED 모두 원자 저장 |
| 대상 조회 | 활성 실제 입장자, auto_submitted_at IS NULL, personal_ends_at + 3초 <= now. 부모·참가 CANCELED와 ABSENT 제외 |
| 상태 전이 | STARTED → FINISHED + auto_submitted_at·참가 이력 |
| 발행 이벤트 | ExamSubmissionRequested |
| 지표 | §4.0 공통 지표 및 대상 상태·회차·버전별 보류 원인 |

**동작:**

1. 부모 → 참가자 잠금 뒤 후보 조건을 다시 확인한다. FIXED의 초기 personalEndsAt은 전체 마감이며, 수동 종료가 요청 시각으로 줄인다.
2. 문제별 전체 언어 최대 seq Draft 하나를 선택한다. 최신 코드가 빈 문자열이면 오래된 언어 코드로 대체하지 않는다. 마지막 접수 seq보다 큰 비어 있지 않은 Draft만 AUTO로 생성한다.
3. AUTO의 received_at은 개인 종료 기준 시각으로 고정한다. 실행 시각으로 바꾸지 않는다. 기존 직접 제출은 다시 요청하지 않는다.
4. 대상이 없어도 auto_submitted_at·FINISHED·이력을 기록한다. 어느 단계든 실패하면 전체 롤백한다.
5. 정지 전 서버에 커밋된 Draft는 정지 후에도 자동 제출한다. 정지·스터디 탈퇴는 이미 접수한 유효성을 소급 취소하지 않는다.

**동시성 설계 포인트:**

- 참가자·문제·seq 유일 제약으로 직접·자동 접수 중복을 방지한다.
- +3초는 실행 지연이며 신규 제출 유예가 아니다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 직접 제출 | 같은 최신 seq 접수 | 1개 접수만 생성 | 참가자 잠금·seq 유일 제약 |
| 수동 종료 API | 동시 자동 처리 | 완료 표식이 있는 참가자 제외 | 같은 원자 처리·후보 재검증 |

**재기동·지연 시:** 미완료 표식의 입장자만 다시 선정한다. 수동 종료 API는 종료 트랜잭션에서 즉시 같은 처리를 수행하며 실패하면 종료 자체가 롤백된다. 정책 §11 "시험 종료 30초 후 자동 제출 누락" 경보는 이 작업의 `auto_submitted_at IS NULL AND personal_ends_at + 30초 < now` 건수로 측정한다.

**명세서와의 관계:** API §4.2.4~§4.2.6 / DB §4.4.3·§4.4.5·§4.4.6 / 정책 §5.3·§5.4

### 4.3 `ExamResultFinalizationScheduler` — 시험 확정

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `contest-service` / `exam` |
| 실행 방식 | `fixedDelay = 10초`, `initialDelay = 10초` |
| 잠금 | ShedLock `ExamResultFinalizationScheduler`. §4.0 임대·행 잠금 순서 적용 |
| 한 회차 처리량 | 페이지당 100건, 20초 뒤 새 선정 중단. 남은 대상은 다음 회차 |
| 트랜잭션 경계 | 시험 1건의 재계산·결과·상태·스냅샷·Outbox를 단일 트랜잭션 |
| 대상 조회 | CLOSED 시험 |
| 상태 전이 | CLOSED → FINALIZED. result_revision 증가·불변 스냅샷 저장 |
| 발행 이벤트 | ExamFinalized |
| 지표 | §4.0 공통 지표 및 대상 상태·회차·버전별 보류 원인 |

**동작:**

1. 부모 잠금 뒤 활성 참가자가 모두 FINISHED/ABSENT인지, 실제 입장자의 자동 제출이 완료됐는지 확인한다.
2. ACCEPTED·REQUESTED가 0건이고 미확인 원격 관리자 작업이 없어야 한다. JUDGED는 종결, FAILED는 is_zero_confirmed=true인 관리자 0점 확정만 종결로 인정한다.
3. 문제별 마지막 유효 접수는 received_at·draft_seq·id 내림차순이다. 이전 AC로 대체하지 않고 해당 제출의 점수·공동 순위를 DB에서 재계산한다.
4. 취소·ABSENT·유효 접수 0건은 순위에서 제외한다. AUTO도 유효 접수다. 완전 동점은 1,1,3 공동 순위로 계산한다.
5. 결과·FINALIZED·result_revision·새 result_snapshots·Outbox를 원자 저장한다. 기존 스냅샷은 보존한다.

**동시성 설계 포인트:**

- FAILED를 자동 오답·0점으로 처리하지 않는다.
- eventId는 전달 멱등, resultRevision은 소비자 최신 결과 교체에 사용한다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 자동 제출·채점 소비 | 확정 조건 검사 중 결과 변경 | 부모 잠금 후 최신 상태로 확정 | 공통 잠금 순서 |
| 재처리 API | 원격 성공 여부 미확인 | 확정 보류 | 관리자 작업 상태·목표 회차 검사 |

**재기동·지연 시:** 조건 미충족이면 다음 10초 주기에서 다시 검사한다. FINALIZED는 자동 후보에서 제외한다. 관리자 재확정은 명시적 API 작업으로 새 스냅샷을 저장한다.

**명세서와의 관계:** API §4.2.7~§4.2.9·§4.6 / DB §6.4.2·§4.4.15·§4.4.18 / 정책 §5 결과 확정

### 4.4 `ContestLifecycleScheduler` — 대회 시작·종료

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `contest-service` / `contest` |
| 실행 방식 | `fixedDelay = 5초`, `initialDelay = 5초` |
| 잠금 | ShedLock `ContestLifecycleScheduler`. §4.0 임대·행 잠금 순서 적용 |
| 한 회차 처리량 | 페이지당 100건, 20초 뒤 새 선정 중단. 남은 대상은 다음 회차 |
| 트랜잭션 경계 | 대회 1건당 1트랜잭션 |
| 대상 조회 | SCHEDULED AND starts_at <= now 또는 RUNNING AND ends_at < now |
| 상태 전이 | SCHEDULED → RUNNING → ENDED |
| 발행 이벤트 | ContestStarted / ContestEnded |
| 지표 | §4.0 공통 지표 및 대상 상태·회차·버전별 보류 원인 |

**동작:**

1. 부모 잠금 뒤 조건을 재검증하고 상태·이력·해당 Outbox를 저장한다. 마감과 같은 시각에는 종료 전이하지 않는다.
2. 대회는 자동 코드 제출을 하지 않는다. 마감 뒤 신규 접수는 거절하고, 마감 전 접수의 지연 채점은 반영한다. ENDED와 FINALIZED를 구분한다.
3. 취소된 대회·참가 이력을 복원하지 않는다. 종료 이벤트는 힌트 차단 해제에 사용하며 사용자 종료 알림을 생성하지 않는다.

**동시성 설계 포인트:**

- 접수 유효 시간은 <= ends_at, 종료 선정은 ends_at < now다.
- 상태·Outbox를 조건부로 한 번만 전이한다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 참가·입장·제출 API | 마감 경계 경합 | 잠금 안의 서버 접수 시각으로 판정 | 공통 접수 규칙 |
| 취소 API | 시작 전 취소 | CANCELED는 시작 후보 제외 | 부모 잠금·상태 재검증 |

**재기동·지연 시:** 시작·종료가 모두 지난 SCHEDULED는 이력을 남기고 ENDED까지 전이한다. 만료된 시작 알림을 새로 예약하지 않는다.

**명세서와의 관계:** API §4.4 / DB §4.4.8·§4.4.10 / 정책 §6

### 4.5 `ContestResultFinalizationScheduler` — 대회 확정

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `contest-service` / `contest` |
| 실행 방식 | `fixedDelay = 10초`, `initialDelay = 10초` |
| 잠금 | ShedLock `ContestResultFinalizationScheduler`. §4.0 임대·행 잠금 순서 적용 |
| 한 회차 처리량 | 페이지당 100건, 20초 뒤 새 선정 중단. 남은 대상은 다음 회차 |
| 트랜잭션 경계 | 대회 1건의 결과·상태·버전·스냅샷·Outbox 원자 저장 |
| 대상 조회 | ENDED 대회, ACCEPTED·REQUESTED 0건, 미해결 FAILED·미확인 관리자 작업 0건 |
| 상태 전이 | ENDED → FINALIZED. result_revision 증가·불변 스냅샷 |
| 발행 이벤트 | ContestFinalized |
| 지표 | §4.0 공통 지표 및 대상 상태·회차·버전별 보류 원인 |

**동작:**

1. 부모 잠금 아래 종결 조건을 재검증한다. FAILED는 is_void_confirmed=true인 관리자 무효 확정만 종결로 인정한다.
2. 참가자·문제별 received_at·id 순으로 최초 AC를 정한다. 그 이전 WA/TLE/MLE/RE만 오답 횟수에 포함한다. CE·SYSTEM_ERROR·관리자 무효·최초 AC 이후 제출은 제외한다.
3. solved_count 내림차순 → penalty_minutes 오름차순 → last_accepted_at 오름차순으로 순위를 계산한다. memberId는 화면 정렬만 안정화한다. 미제출자는 제외한다.
4. DB 확정 후 캐시 갱신이 실패해도 DB를 롤백하지 않는다. §4.8에서 복구한다.

**동시성 설계 포인트:**

- 분 단위 패널티와 정밀 접수 시각을 구분한다.
- 모든 순위 기준이 같으면 공동 순위다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 지연 채점·실패 종결 | 최종 집계 중 제출 상태 변경 | 같은 부모 잠금 아래 재계산 | 미종결·관리자 작업 검사 |
| Redis 갱신 | 확정 후 캐시 장애 | DB 확정 유지·DB 대체 조회 | DB가 순위 원천 |

**재기동·지연 시:** 종결 조건이 충족되지 않으면 다음 10초 주기에 다시 검사한다. FINALIZED는 자동 재확정하지 않는다.

**명세서와의 관계:** API §4.4.8~§4.4.9·§4.6 / DB §4.4.12·§4.4.15·§6.4.2 / 정책 §6

### 4.6 `ContestSubmissionReconciliationScheduler` — 접수·채점 대사

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `contest-service` / `submission` |
| 실행 방식 | `fixedDelay = 5분`, `initialDelay = 5분` |
| 잠금 | ShedLock `ContestSubmissionReconciliationScheduler`. §4.0 임대·행 잠금 순서 적용 |
| 한 회차 처리량 | 페이지당 100건, 20초 뒤 새 선정 중단. 남은 대상은 다음 회차 |
| 트랜잭션 경계 | 원격 조회 후 문맥별 새 트랜잭션에서 접수·결과·버전 재검증 |
| 대상 조회 | 접수 후 1분 이상 ACCEPTED·REQUESTED 및 미해결 FAILED |
| 상태 전이 | 원격 COMPLETED → C JUDGED, FAILED → C FAILED. 허용 회차만 반영 |
| 발행 이벤트 | 새 C 접수 이벤트 없음. 미수신 접수는 기존 Outbox 재전달 |
| 지표 | §4.0 공통 지표 및 대상 상태·회차·버전별 보류 원인 |

**동작:**

1. B의 by-receipt API로 context·receiptId를 조회한다. submissionId·userId·문제·고정 회차·submittedAt을 원래 접수와 대조하며 불일치는 격리·운영 오류 기록한다.
2. DB §6.4.2의 회차 비교와 결과 재계산을 적용한다. judge_attempt는 마지막 종결 반영 회차, 목표 회차는 관리자 작업의 target_judge_attempt다.
3. 작은 회차·같은 회차 동일 결과는 무시한다. 같은 회차 상충은 격리한다. 큰 최초 결과 또는 허용 재처리 회차만 적용한다.
4. B에 없는 접수는 기존 Outbox 상태를 확인해 동일 receiptId·eventId 요청을 재전달한다. 새 C 접수·seq를 만들지 않으며 B receipt 유일 제약이 중복 채점을 막는다.
5. QUEUED/JUDGING/RETRY_WAITING을 임의 FAILED로 바꾸지 않는다. judge_jobs 임대·워커 회수·Run 락 복구는 B가 담당한다(§3.2·§3.5).
6. FINALIZED에 상충하는 새 회차가 발견되면 기존 스냅샷을 보존하고 오류 기록만 남긴다.

**동시성 설계 포인트:**

- 원격 조회 중 DB 행 잠금 없음. 조회 뒤 접수·회차·부모 상태 재검증.
- 소비자와 동일한 원자 결과 반영을 사용한다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 결과 이벤트 소비 | 대사와 동일 회차 수신 | 한 번 반영 또는 동일 결과 무시 | 회차·processed_events·부모 잠금 |
| 재처리 응답 | 목표 결과가 응답보다 먼저 도착 | 이미 반영된 종결 결과 유지 | 마지막 반영 회차와 목표 비교 |

**재기동·지연 시:** 조회 실패·비종결은 지연 지표·운영 알림을 기록하고 다음 대사에서 다시 확인한다. 시간 초과만으로 자동 실패·확정을 하지 않는다.

**명세서와의 관계:** API §4.8.4·부록 B / DB §4.4.6·§4.4.11·§6.4.2

### 4.7 `ContestOperationReconciliationScheduler` — 관리자·자원 준비 복구

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `contest-service` / `operation` |
| 실행 방식 | `fixedDelay = 5분`, `initialDelay = 5분` |
| 잠금 | ShedLock `ContestOperationReconciliationScheduler`. §4.0 임대·행 잠금 순서 적용 |
| 한 회차 처리량 | 페이지당 100건, 20초 뒤 새 선정 중단. 남은 대상은 다음 회차 |
| 트랜잭션 경계 | 원격 확인 후 관리자 작업·접수 또는 가드·감사를 새 트랜잭션에 반영 |
| 대상 조회 | 생성·갱신 후 1분 이상 미종결 원격 관리자 작업과 PREPARED 가드 |
| 상태 전이 | 관리자 PENDING → 확인된 SUCCEEDED/FAILED. 원격 COMMITTED 확인 시 가드 PREPARED → BLOCKED, 원격 ABORTED 확인 시 PREPARED → OPEN |
| 발행 이벤트 | 원격 완료 결과의 기존 이벤트 복구만. 새로운 확정 알림 없음 |
| 지표 | §4.0 공통 지표 및 대상 상태·회차·버전별 보류 원인 |

**동작:**

1. EXAM_RETRY는 같은 operationId·expectedJudgeAttempt로 B 계약(3.3.6)을 재요청하고 접수 상태를 대사한다. 응답 유실로 새 operationId를 만들지 않는다.
2. 성공 목표를 targetJudgeAttempt에 저장한다. 마지막 반영 judge_attempt보다 큰 목표일 때만 REQUESTED·is_zero_confirmed=false로 전이하고 judge_attempt는 유지한다. 이미 목표 이상 결과가 반영됐으면 상태·판정을 보존한다.
3. 확인된 작업 SUCCEEDED·감사 로그를 함께 저장한다. B가 동일 작업 미실행 실패를 확정하면 FAILED·마지막 오류로 종결한다. 타임아웃·5xx·조회 실패는 미실행 증거가 아니다.
4. 가드 소유 서비스 작업 상태를 조회한다. COMMITTED이면 complete 규칙, ABORTED이면 cancel 규칙을 적용한다. PENDING·404·통신 실패는 PREPARED를 유지한다.
5. 가드 잠금 뒤 operationId·sourceVersion을 다시 비교한다. 새 작업·새 버전을 이전 응답으로 덮어쓰지 않는다. 동일 완료·취소는 멱등이다.
6. STUDY 준비로 취소한 예정 시험은 ABORTED여도 자동 복원하지 않는다. 진행 시험은 원래 마감까지 종료 처리를 계속한다. 가드 자동 해제 TTL을 두지 않는다.

**동시성 설계 포인트:**

- 미확인 원격 작업과 미해결 FAILED는 결과 확정을 계속 차단한다.
- 늦은 재처리 응답이 이미 반영한 종결 결과를 되돌리지 않는다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 목표 채점 결과 | 응답 유실 뒤 결과가 먼저 도착 | 목표 이상 종결 상태 유지 | 회차 비교 |
| 새 prepare·소유 상태 이벤트 | 오래된 작업 대사 응답 | 현재 operationId·sourceVersion만 반영 | 가드 잠금·원천 버전 검증 |

**재기동·지연 시:** DB의 미종결 작업을 다시 선정한다. 원격 상태가 불명확하면 차단을 유지하고 운영 알림을 남긴다. 접수 FAILED는 별도 관리자 종결 전까지 확정을 막는다.

**명세서와의 관계:** API §4.7.3~§4.7.4·§4.8.9·§4.6.3~§4.6.7 / DB §4.4.16·§4.4.18. 자원 가드는 도입 결정 대기(API 부록 D) — 미도입 시 가드 관련 동작 4~6은 비활성

### 4.8 `ContestCacheRebuildScheduler` — 순위 캐시 복구

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `contest-service` / `result` |
| 실행 방식 | `fixedDelay = 5분`, `initialDelay = 5분` |
| 잠금 | ShedLock `ContestCacheRebuildScheduler`. §4.0 임대·행 잠금 순서 적용 |
| 한 회차 처리량 | 페이지당 100건, 20초 뒤 새 선정 중단. 남은 대상은 다음 회차 |
| 트랜잭션 경계 | DB 동일 버전 일관 읽기 → 잠금 없는 캐시 생성 → Redis Lua 원자 교체 |
| 대상 조회 | DB 결과가 있는 전체 시험·대회. Redis 존재·result_revision 대조 |
| 상태 전이 | DB 상태 변경 없음. 최신 버전 Redis 포인터·데이터 교체 |
| 발행 이벤트 | 없음. 사용자 알림·재확정 이벤트를 새로 발행하지 않음 |
| 지표 | §4.0 공통 지표 및 대상 상태·회차·버전별 보류 원인 |

**동작:**

1. ID 순으로 한 바퀴 대조한다. 실행 한도에 도달하면 다음 회차는 마지막 ID 다음부터 이어가며 한 바퀴 후 처음으로 돌아간다.
2. 같은 result_revision 결과를 일관된 읽기로 확보한다. 생성 중 DB 버전이 바뀌면 폐기하고 최신 버전으로 재선정한다.
3. 대회 ZSET score는 모두 0. lex 키는 (20-solvedCount) 2자리, 패널티 분 20자리, 마지막 AC UTC epoch 마이크로초 20자리, memberId 20자리를 콜론으로 연결하고 0으로 왼쪽 패딩한다. 미해결자의 마지막 AC는 최대값이다.
4. 공동 순위는 memberId를 제외한 앞 세 값으로 판정한다. 부동소수 합성 score는 사용하지 않는다(정책 §6 합성 점수 초안 대체, 역반영 필요). 값 상한 초과는 캐시 오류·DB 조회로 처리한다.
5. 새 버전 키 전체 생성 후 Lua로 작은 버전 교체를 거절하고 포인터·버전을 원자 교체한다. 같은 버전 재구성은 동일 결과만 만들며 이전 키는 교체 성공 후 삭제한다.
6. API는 캐시 없음·버전 불일치 때 DB를 조회한다. 시험 점수 캐시도 같은 검증을 적용한다. SSE 재연결은 현재 DB 버전을 안내하며 전송 성공을 결과 저장 성공으로 간주하지 않는다.

**동시성 설계 포인트:**

- Redis는 원천 DB 결과를 변경하지 않는다.
- 같은 result_revision 전체 집합을 교체해 부분 캐시 노출을 막는다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 채점·확정 갱신 | 캐시 생성 중 DB 버전 증가 | 생성본 폐기·최신 버전 재생성 | 일관 읽기·교체 전 버전 확인 |
| 캐시 재구성 중복 | ShedLock 임대 후 중복 실행 | 최신 버전 포인터만 유지 | Redis Lua 버전 검사 |

**재기동·지연 시:** 순회 커서는 처음부터 시작해도 안전하다. 재구성 실패는 다음 5분 주기에 재시도하며 사용자 조회는 DB를 사용한다.

**명세서와의 관계:** API §4.2.8·§4.4.8·§4.3.1·§4.5.1 / DB §6.4.2

### 4.9 서비스 간 연동

| 항목 | 값 |
| --- | --- |
| A 알림 | 등록·변경·취소로 시험 5분 전 예약 관리(notification §7.1), 최종 결과로 확정 알림. C 종료·잠정·건별 완료 알림 없음 |
| B 채점 | 접수 미전달은 §4.6이 같은 eventId로 재전달, 작업 회수·Run 락은 judge §3.2·§3.5 |
| D 힌트 | API §4.7.7 hint-blocks로 복구. 취소·재등록은 새 participantId. 전체 종료·CANCELED는 차단 제외. 실제 입장은 탈퇴 차단 조건이며 등록 힌트 조건과 구분 |

---

## 5. 스터디 (study)

> 작성: 담당 D · 서비스: `study-service`
> 

**검토 메모 (v1.0 통합):** 본문은 작성본을 유지했다. 정정: cron에 `zone = "Asia/Seoul"` 명시(5.4·5.5), 이벤트 필드는 이벤트 계약서 S3·S5~S7에 맞춤(`incompleteMemberIds`, `recipientMemberIds`), 스터디 종료 경로(5.0)는 API 5.3.4 설명과 순서가 달라 API 쪽을 이 문서 순서로 역반영 요청.

- `StudyApplicationExpiryScheduler` — 1시간, 7일 지난 `PENDING` 가입 신청 만료
- `AssignmentLifecycleScheduler` — **1분**, `SCHEDULED → OPEN`(기존 풀이 반영 포함), 마감 도달 `OPEN → CLOSED`와 미완료 멤버 `INCOMPLETE`
- `AssignmentDeadlineReminderScheduler` — 10분, 마감 24시간 이내 문제집 미완료 멤버 알림 (문제집당 1회)
- `RecommendationRefreshScheduler` — 매일 03:00, 추천 결과 갱신
- `StudyCounterReconciliationScheduler` — 매일 04:00, 정원·운영진·참여 상한 카운터 대사 (탐지·알림만)

**경합 확인 대상**

- 만료 시점의 승인·거절·취소
- `OPEN` 전이와 동시에 도착한 `SubmissionJudged`
- 가입 승인 트랜잭션과 `OPEN` 전이 동시 발생
- 마감 시점에 도착한 AC 이벤트 (접수 시각 기준이라 `LATE`/`ON_TIME` 판정은 도착 시각과 무관)
- 스터디 종료와 문제집 전이 동시 발생

### 5.0 잠금 순서 규칙 (study-service 공통)

스터디 서비스의 트랜잭션은 아래 순서로만 행을 잠근다. 역순으로 잡는 경로가 없으므로 교착 상태가 생기지 않는다.

```
① study_member_quotas  →  ② studies  →  ③ assignments (id 오름차순)
   →  ④ study_memberships  →  ⑤ member_ac_replicas
   →  ⑥ assignment_member_statuses · assignment_completions
```

| 경로 | 잠그는 순서 | 비고 |
| --- | --- | --- |
| 가입 승인·즉시 가입 (API) | ① UPDATE → ② UPDATE → ③ `FOR SHARE` → ④ UPDATE/INSERT → ⑤ `FOR SHARE` → ⑥ upsert | ③을 ④보다 먼저 잡는 것이 핵심 |
| 문제집 `OPEN` 전이 (5.2) | ③ UPDATE → ④ `FOR SHARE` → ⑤ `FOR SHARE` → ⑥ upsert | 잠금 읽기는 항상 최신 커밋을 본다 |
| `SubmissionJudged` 반영 (이벤트) | ③ `FOR SHARE` → ⑤ upsert → ⑥ 재계산 | 복제본을 쓰기 **전에** 문제집 행을 잡는다 |
| 스터디 종료 (API) | ① 구성원 카운터 `FOR UPDATE`(`member_id` 오름차순) → ② UPDATE → ③ UPDATE → ④ UPDATE → ① 차감 | 카운터를 먼저 잡아야 가입 경로와 순환하지 않는다 |
| 회원 탈퇴 전파 (`MemberWithdrawn`) | ① 해당 회원 카운터 → ② 가입한 스터디들(`id` 오름차순) → ④ | 가입 경로와 같은 방향 |

**스터디 종료의 순서:**

- 종료는 구성원 전원의 카운터를 차감하므로 ①을 여러 행 잡는다.
- 이때도 ①을 ②보다 먼저, `member_id` 오름차순으로 잡는다.
- 가입 경로가 같은 회원의 ①을 쥐고 있으면 종료가 ①에서 기다리고, 가입 경로는 ②를 먼저 얻어 커밋.
- 종료는 이후 ④를 다시 읽어 방금 가입한 구성원까지 포함해 차감한다.
- ①을 ② 뒤에 잡으면 가입 경로(①→②)와 순환 대기가 생기므로 금지한다.

### 5.1 `StudyApplicationExpiryScheduler` — 가입 신청 만료

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `study-service` / `membership` |
| 실행 방식 | `fixedDelay = 1시간`, `initialDelay = 1분` |
| 잠금 | ShedLock `StudyApplicationExpiryScheduler`, `lockAtMostFor = 10분` |
| 한 회차 처리량 | 최대 500건, 남으면 다음 회차 |
| 트랜잭션 경계 | `StudyMembershipExpiryService.expire(membershipId)`, 1건당 1트랜잭션 |
| 대상 조회 | `status = 'PENDING' AND expires_at <= now()` / `idx_study_memberships_status_expires_at` |
| 상태 전이 | `study_memberships`: `PENDING → EXPIRED` + `study_membership_histories` INSERT |
| 발행 이벤트 | `StudyApplicationDecided`(`result = EXPIRED`) |
| 지표 | 만료 건수, 실패 건수 |

**동작:** 승인 대기 7일이 지난 신청을 `EXPIRED`로 바꾸고 이력과 이벤트를 같은 트랜잭션에 기록한다.

**동시성 설계 포인트:**

- `UPDATE study_memberships SET status = 'EXPIRED' WHERE id = :id AND status = 'PENDING'`. 0행이면 이미 처리된 것이므로 이력·이벤트 없이 넘어간다.
- 승인 API는 `WHERE status = 'PENDING' AND expires_at > now()`로 처리하므로, **스케줄러가 아직 돌지 않았어도 만료 시각이 지난 신청은 승인되지 않는다.**
- 만료되면 생성 컬럼 `active_member_id`가 NULL이 되어 곧바로 재신청할 수 있다.
- 정원·카운터는 `PENDING`에서 확보하지 않으므로 만료 시 되돌릴 것이 없다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 승인 API | 만료 직전 승인과 스케줄러가 동시 | 먼저 커밋한 쪽만 반영 | 양쪽 `status = 'PENDING'` 조건부 UPDATE |
| 승인 API | 만료 시각 경과, 스케줄러 실행 전 승인 | `STUDY_APPLICATION_EXPIRED` | 승인 조건 `expires_at > now()` |
| 신청 취소 API | 취소와 만료 동시 | 먼저 커밋한 쪽만 반영 | 같은 조건부 UPDATE |
| 회원 탈퇴 이벤트 | 탈퇴 전파(`CANCELED`)와 만료 동시 | `CANCELED` 또는 `EXPIRED` 하나 | 같은 조건부 UPDATE, 어느 쪽이든 활성 신청 해제 |
| 스터디 종료 API | 종료가 대기 신청을 `CANCELED`로 일괄 전이 | 먼저 커밋한 쪽만 반영 | 같은 조건부 UPDATE |

**재기동·지연 시:** 밀린 대상은 재기동 후 첫 회차부터 처리한다. 승인 API가 시각을 직접 확인하므로 지연 중에도 만료 신청은 승인되지 않는다.

**명세서와의 관계:** 기획서 §3.2 T4-02 / 정책 §7 신청 취소·만료 / API 5.2.5 / ERD 4.5.2 `idx_study_memberships_status_expires_at`

### 5.2 `AssignmentLifecycleScheduler` — 문제집 상태 전이

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `study-service` / `assignment` |
| 실행 방식 | `fixedDelay = 1분`, `initialDelay = 30초` |
| 잠금 | ShedLock `AssignmentLifecycleScheduler`, `lockAtMostFor = 50초` |
| 한 회차 처리량 | `OPEN` 전이 최대 100건, `CLOSE` 전이 최대 100건 |
| 트랜잭션 경계 | 문제집 1건당 1트랜잭션 (기존 풀이 반영 포함) |
| 대상 조회 (OPEN) | `status = 'SCHEDULED' AND starts_at <= now()` / `idx_assignments_status_starts_at` |
| 대상 조회 (CLOSE) | `status = 'OPEN' AND deadline_at IS NOT NULL AND deadline_at <= now()` / `idx_assignments_status_deadline_at` |
| 상태 전이 | `assignments`: `SCHEDULED → OPEN`, `OPEN → CLOSED` / `assignment_member_statuses`: 미완료 → `INCOMPLETE` |
| 발행 이벤트 | `AssignmentPublished`(OPEN), `AssignmentIncompleteRepeated`(CLOSE, 해당 멤버가 있을 때) |
| 지표 | OPEN 건수, CLOSE 건수, 기존 풀이 반영 건수, 연속 미완료 알림 건수, 실패 건수 |

**동작 — OPEN 전이 (한 트랜잭션):**

1. `UPDATE assignments SET status = 'OPEN' WHERE id = :id AND status = 'SCHEDULED'`. 0행이면 종료.
2. 해당 스터디의 `APPROVED` 가입을 **잠금 읽기(`FOR SHARE`)**로 조회해 `assignment_member_statuses`를 upsert한다(생성 시점 이후 가입자 보정).
3. `is_pre_solved_allowed = true`이면 `member_ac_replicas`를 잠금 읽기로 조회해, 대상 멤버·`ACTIVE` 문제마다 최초 유효 AC를 근거로 `assignment_completions`를 upsert한다(유형은 접수 시각으로 계산하므로 시작 전 AC는 `PRE_SOLVED`).
4. 멤버별 `completed_count`·`on_time_count`·`status`를 재계산한다.
5. Outbox에 `AssignmentPublished` 기록.

**동작 — CLOSE 전이 (한 트랜잭션):**

1. `UPDATE assignments SET status = 'CLOSED', closed_at = now() WHERE id = :id AND status = 'OPEN' AND deadline_at <= now()`.
2. `assignment_member_statuses` 중 `COMPLETED`가 아닌 행을 `INCOMPLETE`로 전이한다. 탈퇴한 멤버(가입 `LEFT`·`REMOVED`)는 제외한다.
3. `INCOMPLETE`가 된 멤버 중 **같은 스터디의 직전 마감 문제집에서도 `INCOMPLETE`*였던 멤버를 모아, 있으면 `AssignmentIncompleteRepeated`를 기록한다(수신자 = `LEADER`·`MANAGER`, 자동 강퇴 없음).

**동시성 설계 포인트:**

- **이벤트와 개설의 경합:** 이벤트 처리 경로는 복제본을 쓰기 전에 대상 문제집 행을 `FOR SHARE`로 잡는다(5.0). 개설이 먼저면 이벤트는 개설 커밋을 기다린 뒤 `OPEN`을 보고 직접 반영하고, 이벤트가 먼저면 개설은 이벤트 커밋을 기다린 뒤 잠금 읽기로 새 복제본을 본다. 어느 쪽이든 누락되지 않는다.
- **가입과 개설의 경합:** 가입 트랜잭션도 가입 행을 바꾸기 전에 문제집 행을 `FOR SHARE`로 잡는다. 개설이 먼저면 가입이 `OPEN`을 보고 완료 기록까지 만들고, 가입이 먼저면 개설이 잠금 읽기로 새 구성원을 본다. 결과는 유일 제약 upsert로 합쳐진다.
- **잠금 읽기가 필요한 이유:** `REPEATABLE READ`에서 일반 SELECT는 트랜잭션 첫 읽기 시점의 스냅샷을 보므로, 기다린 뒤에도 방금 커밋된 행을 못 볼 수 있다. 2·3단계는 반드시 `FOR SHARE`로 읽는다.
- 마감 이후 도착한 AC도 인정은 계속된다. `CLOSED`(마감)된 문제집의 `INCOMPLETE` 멤버가 모두 인정되면 이벤트 처리 경로가 `COMPLETED`로 되돌린다(정책 §10 `INCOMPLETE → COMPLETED`).

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| `SubmissionJudged` 반영 | OPEN 전이와 AC 이벤트 동시 | 완료 기록 1건, 유형은 접수 시각으로 결정 | 문제집 행 선잠금 + 잠금 읽기 + 유일 제약 upsert |
| 가입 승인·즉시 가입 | OPEN 전이와 가입 동시 | 과제 상태·완료 기록 누락·중복 없음 | 문제집 행 선잠금 + 유일 제약 upsert |
| 스터디 종료 API | 종료와 OPEN·CLOSE 전이 동시 | 먼저 커밋한 쪽 성공, 나머지 0행 | 양쪽 조건부 UPDATE (`status` 조건) |
| 마감 직후 AC 이벤트 | CLOSE 전이와 AC 동시 | CLOSE 후 이벤트가 `INCOMPLETE → COMPLETED` 복귀 가능 | 이벤트 경로가 `CLOSED`(마감)도 반영 대상으로 조회 |
| `ProblemStateChanged`(비공개) | OPEN 전이 중 문제 `EXCLUDED` | 먼저 커밋한 쪽 기준으로 분모 재계산 | 문제집 행 잠금 공유 |
| 동일 스케줄러 중복 실행 | 다중 인스턴스 | 1개 인스턴스만 실행 | ShedLock |

**재기동·지연 시:** 시각 도달 조건으로 밀린 전이를 처리한다. 개설이 늦어져도 완료 유형은 접수 시각으로 계산되므로 결과가 바뀌지 않는다.

**명세서와의 관계:** 기획서 §3.2 T4-04 / 정책 §7.1(D 변경 7·8·9) / API 5.4.3 / ERD 4.5.5~4.5.9

### 5.3 `AssignmentDeadlineReminderScheduler` — 마감 24시간 전 알림

> `AssignmentLifecycleScheduler`와 분리한다. 알림 실패가 상태 전이를 막으면 안 되기 때문이다.
> 

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `study-service` / `assignment` |
| 실행 방식 | `fixedDelay = 10분`, `initialDelay = 5분` |
| 잠금 | ShedLock `AssignmentDeadlineReminderScheduler`, `lockAtMostFor = 8분` |
| 한 회차 처리량 | 최대 100건 |
| 트랜잭션 경계 | 문제집 1건당 1트랜잭션 |
| 대상 조회 | `status = 'OPEN' AND reminder_sent_at IS NULL AND deadline_at > now() AND deadline_at <= now() + INTERVAL 24 HOUR` / `idx_assignments_status_deadline_at` |
| 상태 변경 | `UPDATE assignments SET reminder_sent_at = now() WHERE id = :id AND reminder_sent_at IS NULL AND status = 'OPEN'` |
| 발행 이벤트 | `AssignmentDeadlineApproaching`(`incompleteMemberIds`) |
| 지표 | 알림 이벤트 기록 건수 |

**동작:** 마감이 24시간 안으로 들어온 문제집마다 한 번, 미완료 멤버 목록을 담아 이벤트를 기록한다.

**동시성 설계 포인트:**

- `reminder_sent_at IS NULL` 조건부 UPDATE와 Outbox 기록이 같은 트랜잭션이라 **문제집당 정확히 1회**다. 이전 초안의 "23~24시간 구간" 조회는 서비스가 1시간 이상 멈추면 알림이 영영 누락되므로, "24시간 이내이고 아직 안 보냄"으로 바꿨다.
- 생성 시점부터 마감이 24시간 이내인 문제집은 다음 회차에 바로 알림이 나간다.
- 미완료 멤버가 0명이면 `reminder_sent_at`만 기록하고 이벤트는 생략한다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| `AssignmentLifecycleScheduler` | CLOSE 전이와 동시 | 둘 중 먼저 커밋한 쪽 기준, 닫힌 문제집은 알림 제외 | 조건 `status = 'OPEN'` |
| 동일 스케줄러 중복 실행 | 다중 인스턴스 | 1개 인스턴스만 실행 | ShedLock + `reminder_sent_at` 조건 |

**명세서와의 관계:** 정책 §7.1 미달 알림, §2.1 과제 마감 알림 / ERD 4.5.5 `reminder_sent_at`

### 5.4 `RecommendationRefreshScheduler` — 추천 결과 갱신

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `study-service` / `recommendation` |
| 실행 방식 | `cron = 0 0 3 * * *`, `zone = "Asia/Seoul"` |
| 잠금 | ShedLock `RecommendationRefreshScheduler`, `lockAtMostFor = 1시간` |
| 한 회차 처리량 | 프로필 복제본 전체, 200명 단위 청크 |
| 트랜잭션 경계 | 회원 1명당 1트랜잭션 (기존 결과 교체) |
| 대상 조회 | `member_learning_profile_replicas` 전체 |
| 처리 | 규칙 점수 계산 → 회원의 `study_recommendations` 교체 (상위 50건) |
| 추가 트리거 | `LearningProfileUpdated` 수신 시 해당 회원 즉시 재계산, 조회 시 1일 경과면 즉시 재계산 |
| 지표 | 재계산 회원 수, 소요 시간(ms), 실패 수 |

**동작:** 점수 = 목표 일치(×40) + 실력 구간(×30) + 선호 언어(×20) + 활동 시간(×10), 0~100점. 가중치는 설정값이며 5주차에 확정한다(AI 설계 문서 §3.2).

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| `LearningProfileUpdated` 즉시 재계산 | 배치 중 같은 회원 이벤트 도착 | 나중 커밋이 최종. 이벤트 처리는 최신 프로필로 계산하므로 결과가 최신 | 회원 단위 교체 트랜잭션 |
| 조회 시 즉시 재계산 | 배치와 조회가 같은 회원 재계산 | 같은 입력이면 같은 결과, 나중 커밋이 최종 | 회원 단위 교체 트랜잭션 |
| `MemberWithdrawn` 소비 | 배치가 탈퇴 회원 재계산 | 프로필 복제본 삭제 후엔 대상 아님, 재계산 직전 복제본 존재 재확인 | 복제본 행 잠금 |
| 동일 스케줄러 중복 실행 | 다중 인스턴스 | 1개 인스턴스만 실행 | ShedLock |

**명세서와의 관계:** 정책 §8.2 / API 5.1.4 / ERD 4.5.10·4.5.11

### 5.5 `StudyCounterReconciliationScheduler` — 카운터 대사

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `study-service` / `membership` |
| 실행 방식 | `cron = 0 0 4 * * *`, `zone = "Asia/Seoul"` |
| 잠금 | ShedLock `StudyCounterReconciliationScheduler`, `lockAtMostFor = 30분` |
| 처리 | `studies.member_count` ↔ `APPROVED` 수, `manager_count` ↔ `MANAGER` 수, `study_member_quotas.active_count` ↔ 비종료 스터디 `APPROVED` 수 비교 |
| 상태 변경 | 없음 (탐지·기록만). 불일치는 로그·지표·운영 알림 |
| 지표 | 불일치 건수 (0이 정상) |

**동작:** 카운터는 가입·탈퇴와 같은 트랜잭션에서만 바뀌므로 정상이면 불일치가 없다. 불일치는 버그 신호이므로 자동 보정하지 않고 알린다. 보정은 원인 확인 후 관리자 절차로 한다. 비교 쿼리는 잠금 없는 읽기라 진행 중 가입으로 일시 불일치가 보일 수 있으므로, 불일치 행은 5초 뒤 한 번 더 확인하고 그때도 다르면 기록한다.

**명세서와의 관계:** 정책 §7 정원 검증·참여 상한(D 변경 1·2) / ERD 6.5

---

## 6. AI 기능 (ai)

> 작성: 담당 D · 서비스: `study-service` AI 모듈 (레포의 `ai-service` 모듈 분리 여부는 미정, API 6장 표기 "`ai-service` 후보")
> 

**검토 메모 (v1.0 통합):** 정정 — 6.1 `REQUESTED` 회수 조건은 `generation_started_at`이 NULL이라 기존 인덱스를 못 쓰므로 `idx_hint_requests_status_created_at(status, created_at)` 추가를 ERD에 요청. 회수 시 진단 중 차단(`blocked_reason = DIAGNOSIS`)도 재확인 대상에 포함(판정 경로 확정 후). `hint_daily_usages` 정리 배치(6.3)를 추가했다.

- `HintGenerationTimeoutScheduler` — 1분, 생성 도중 멈춘 요청을 폴백으로 회수
- `HintBlockReplicaCleanupScheduler` — 매일 05:00, 종료 1일 지난 차단 복제본 정리
- `HintDailyUsageCleanupScheduler` — 매일 05:10, 90일 지난 일일 사용량 행 정리 (신규)
- 풀이 비교 분석 회수 — P3 후보, 이번 범위 제외

> 힌트 캐시는 Redis TTL(30일)로 만료되고 키에 회차가 있어 새 회차 공개 시 자연 무효화된다. 일일 사용량은 날짜별 행이라 자정 초기화가 필요 없다.
> 

### 6.1 `HintGenerationTimeoutScheduler` — 힌트 생성 회수

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `study-service` / `hint` |
| 실행 방식 | `fixedDelay = 1분`, `initialDelay = 1분` |
| 잠금 | ShedLock `HintGenerationTimeoutScheduler`, `lockAtMostFor = 50초` |
| 한 회차 처리량 | 최대 100건 |
| 트랜잭션 경계 | 1건당 1트랜잭션 |
| 대상 조회 | ① `status = 'GENERATING' AND generation_started_at <= now() - INTERVAL 1 MINUTE` / `idx_hint_requests_status_generation_started_at` ② `status = 'REQUESTED' AND created_at <= now() - INTERVAL 1 MINUTE` / `idx_hint_requests_status_created_at`(추가 요청) |
| 상태 전이 | `GENERATING·REQUESTED → FALLBACK`(`fallback_reason = RECOVERED`) 또는 `→ BLOCKED`(재확인 결과 시험·진단 중) |
| 발행 이벤트 | `HintReady`(`result = FALLBACK` 또는 `BLOCKED`) |
| 지표 | 회수 건수, 그중 BLOCKED 건수 |

**동작:** 생성 도중 서버가 죽거나 비동기 작업이 유실돼 1분 넘게 멈춘 요청을 회수한다. 일반 종결과 같은 **전달 직전 차단 재확인**을 거쳐, 시험 중이면 `BLOCKED`(일일 횟수 1 반환), 아니면 태그 기반 정적 힌트로 `FALLBACK`한다.

**동시성 설계 포인트:**

- `UPDATE hint_requests SET status = :next, … WHERE id = :id AND status = :current`. 0행이면 그사이 LLM 응답으로 종결된 것이므로 넘어간다.
- 늦게 도착한 LLM 응답은 같은 조건(`status = 'GENERATING'`)을 만족하지 못해 자동으로 버려진다.
- 1분 기준은 LLM 타임아웃(15초) + 재생성 1회 + 여유를 합친 값이다.
- 차단 복제본 조회가 실패하면(fail-closed) `BLOCKED`로 종결한다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| LLM 응답 완료 | 회수와 `READY` 전이 동시 | 먼저 커밋한 쪽만 반영 | 양쪽 `status = 'GENERATING'` 조건부 UPDATE |
| 비동기 생성 시작 | `REQUESTED` 회수와 `GENERATING` 전이 동시 | 먼저 커밋한 쪽만 반영, 생성 시작이 지면 워커는 작업 포기 | `status = 'REQUESTED'` 조건부 UPDATE |
| 시험 참가 등록 이벤트 | 회수 직전 차단 복제본 생성 | 회수 시 재확인에서 `BLOCKED` | 전달 직전 재확인 |
| 동일 스케줄러 중복 실행 | 다중 인스턴스 | 1개 인스턴스만 실행 | ShedLock |

**재기동·지연 시:** 재기동 후 첫 회차에 밀린 요청을 일괄 회수한다.

**명세서와의 관계:** 정책 §8.1 타임아웃·폴백, 시험·대회 중 차단(D 변경 11) / API 6.1.3 / ERD 4.6.1

### 6.2 `HintBlockReplicaCleanupScheduler` — 차단 복제본 정리

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `study-service` / `guardrail` |
| 실행 방식 | `cron = 0 0 5 * * *`, `zone = "Asia/Seoul"` |
| 잠금 | ShedLock `HintBlockReplicaCleanupScheduler`, `lockAtMostFor = 30분` |
| 한 회차 처리량 | 1,000건 단위 반복 삭제 |
| 대상 조회 | `ends_at < now() - INTERVAL 1 DAY` / `idx_hint_block_replicas_ends_at` |
| 처리 | 물리 삭제 (기간 데이터) |
| 지표 | 삭제 건수, 종료 이벤트 없이 남아 있던 건수(유실 추정) |

**동작:** 종료·취소 이벤트로 지워지지 않은 행(이벤트 유실·DLQ)을 정리한다. 이런 행은 이미 `ends_at + 10분` 이후로는 차단에 쓰이지 않으므로 정리 시점은 판정에 영향이 없다. "이벤트 없이 남은 건수"는 종료 이벤트 유실을 감지하는 지표로 쓴다.

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 종료 이벤트 처리 | 같은 행을 동시에 삭제 | 둘 다 성공 처리(0행 삭제는 정상) | 삭제는 멱등 |
| 늦게 도착한 참가 등록 이벤트 | 종료 후 참가 등록 이벤트 도착 | 종단 표식으로 무시. 표식이 없어도 `ends_at`이 지나 차단에 쓰이지 않고 다음 회차에 삭제 | 종단 표식·시각 판정 |

**명세서와의 관계:** 정책 §8.1(D 변경 11), §2.1 힌트 차단 만료 여유 / ERD 4.6.3

### 6.3 `HintDailyUsageCleanupScheduler` — 일일 사용량 정리 (신규)

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `cron = 0 10 5 * * *`, `zone = "Asia/Seoul"` |
| 잠금 | ShedLock `HintDailyUsageCleanupScheduler`, `lockAtMostFor = 10분` |
| 처리 | `usage_date < CURRENT_DATE - INTERVAL 90 DAY` 1,000건 단위 삭제 |
| 경합 | 오늘 행만 갱신되므로 대상이 겹치지 않음 |

## 7. 알림 (notification)

> 작성: 담당 A · 서비스: `notification-service`
> 
- `ExamReminderDispatchScheduler` — 10초, 시험 시작 5분 전 예약 알림 발화
- `NotificationRetentionScheduler` — 매일 03:30, 90일 지난 알림 삭제

> SSE 하트비트(30초)·연결 주기 종료(60초)는 연결별 타이머이며 DB 상태를 바꾸지 않으므로 스케줄러로 관리하지 않는다. 단일 인스턴스 메모리 연결 관리(API 7.0).
> 

### 7.1 `ExamReminderDispatchScheduler` — 시험 시작 임박 알림

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `notification-service` / `reminder` |
| 실행 방식 | `fixedDelay = 10초`, `initialDelay = 10초` |
| 잠금 | ShedLock `ExamReminderDispatchScheduler`, `lockAtMostFor = 9초` |
| 예약 저장소 | `notification_reminders`(ERD 추가 제안): `member_id`, `exam_id`, `participant_id`, `fire_at`, `starts_at`, `context_revision`, `source_event_id`, `status`(`SCHEDULED`/`SENT`/`CANCELED`) / 유일 `(participant_id)` |
| 예약 생성·갱신 | `ExamParticipantRegistered` → `fire_at = startsAt − 5분` INSERT. `ExamUpdated` → `context_revision` 큰 경우만 `fire_at` 갱신. `ExamParticipantCanceled`·`ExamCanceled` → `CANCELED` |
| 대상 조회 | `status = 'SCHEDULED' AND fire_at <= now() AND starts_at > now()` |
| 처리 | `UPDATE … SET status='SENT' WHERE id=:id AND status='SCHEDULED'` + `EXAM_STARTING_SOON` 알림 INSERT(유일 제약) 한 트랜잭션 → 커밋 후 SSE 전송 |
| 지표 | 발화 건수, `fire_at` 대비 지연 |

**동작:** 이미 임박(등록 시점에 `fire_at <= now < starts_at`)이면 다음 회차에 즉시 1회 발화한다. `starts_at`이 지난 예약은 발화하지 않고 `CANCELED`로 정리한다(지난 시작 알림 금지, 스케줄러 §4.1과 동일 원칙).

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| `ExamUpdated` 소비 | 발화 직전 시작 시각 변경 | 먼저 커밋한 쪽. 발화가 먼저면 이미 보낸 알림 유지 + `EXAM_UPDATED` 알림 별도 | `status='SCHEDULED'` 조건부 UPDATE |
| `ExamCanceled` 소비 | 발화와 취소 동시 | 둘 중 하나 | 동일 |
| 수신 설정 off | 발화 시점에 설정 꺼짐 | `SENT`로만 표시, 알림 미생성 | 발화 시점 설정 조회 |

**명세서와의 관계:** 정책 §9.2 시험 시작 임박 / API 7.0 시험 시작 예약 / 협의 A-1

### 7.2 `NotificationRetentionScheduler` — 알림 보관 정리

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `cron = 0 30 3 * * *`, `zone = "Asia/Seoul"` |
| 잠금 | ShedLock `NotificationRetentionScheduler`, `lockAtMostFor = 30분` |
| 처리 | `created_at < now() - INTERVAL 90 DAY` 1,000건 단위 삭제 (`idx_notifications_created_at`), `SENT`·`CANCELED` 예약 7일 경과분 삭제 |
| 경합 | SSE 재전송 범위(24시간)와 겹치지 않음 |

**명세서와의 관계:** 정책 §2.1 알림 보관 90일 / ERD 4.7.1

## 8. GitHub 연동 (integration)

> 작성: 담당 A · 서비스: `integration-service`
> 
- `GitHubSyncJobWorker` — 5초, Sync Job 선점·커밋 (워커)
- `GitHubSyncStuckJobRecoveryScheduler` — 1분, `RUNNING` 정체 작업 회수

> GitHub App 설치 토큰은 실행 시마다 1시간짜리를 발급하므로 토큰 갱신 스케줄러가 없다.
> 

### 8.1 `GitHubSyncJobWorker` — Sync Job 실행

| 항목 | 값 |
| --- | --- |
| 소유 서비스·패키지 | `integration-service` / `sync` |
| 실행 방식 | `fixedDelay = 5초` 폴링 |
| 잠금 | ShedLock 없음. `SELECT … WHERE status IN ('PENDING','RETRY_WAITING','RATE_LIMITED') AND (next_retry_at IS NULL OR next_retry_at <= now()) ORDER BY id LIMIT 10 FOR UPDATE SKIP LOCKED` → `RUNNING`, `updated_at = now()` 커밋 |
| 트랜잭션 경계 | ① 선점 Tx ② Tx 밖: 연동 상태 확인 → 설치 토큰 발급 → judge 3.3.3 코드 조회 → Contents API PUT ③ 결과 Tx: `WHERE id=:id AND status='RUNNING'` 조건부 UPDATE |
| 상태 전이 | 201/200 → `SUCCEEDED`(`commit_sha`). 403·429·잔여 호출 기준 이하 → `RATE_LIMITED`(`next_retry_at = x-ratelimit-reset`). 5xx·네트워크·코드 조회 실패 → `RETRY_WAITING`(`retry_count + 1`, 1분 → 5분 → 30분). `retry_count > 5` → `FAILED` + `GitHubSyncFailed`. 401 → 작업 `FAILED`, 연동 `DISCONNECTED`(`AUTH_EXPIRED`), 대기 작업 `CANCELED`, `GitHubSyncFailed`(`AUTH_EXPIRED`) |
| 커밋 경로 | `commit_path_rule` = `{난이도}/{문제번호}-{문제명}/{Solution.ext}` + `README.md`. 같은 문제 재AC는 같은 경로에 새 커밋(기존 파일 `sha` 조회 후 PUT) |
| 지표 | 성공·실패·Rate Limit 건수, 대기 시간 |

**동시성 설계 포인트:**

- 제출당 작업 1건(`uk_github_sync_jobs_submission`)이고 `SKIP LOCKED`라 같은 제출을 두 번 커밋하지 않는다.
- 같은 경로에 같은 회원의 두 작업이 동시 PUT하면 409(sha 불일치)가 난다 → `RETRY_WAITING`으로 재시도하며 최신 sha를 다시 읽는다.
- 결과 저장 직전 연동이 해제됐으면 조건부 UPDATE가 `CANCELED`에 막혀 0행이다(커밋 자체는 이미 반영됐을 수 있음, 파일 삭제 안 함 정책).

**경합 시나리오:**

| 경합 상대 | 상황 | 결과 | 근거 |
| --- | --- | --- | --- |
| 연동 해제 API | 실행 중 해제 | 대기 작업만 `CANCELED`, 실행 중 작업은 결과 저장 0행 후 종료 | `WHERE status='RUNNING'` |
| 수동 동기화 API | `FAILED` 재요청과 워커 | `FAILED → PENDING`(재시도 초기화) 후 다음 폴링 | 조건부 UPDATE |
| `MemberWithdrawn` 소비 | 탈퇴와 실행 동시 | 해제와 같음 | 동일 |

**명세서와의 관계:** 정책 §9.3 / API 8.1.5·8.1.7 / ERD 4.8.2, 6.8

### 8.2 `GitHubSyncStuckJobRecoveryScheduler` — 정체 작업 회수

| 항목 | 값 |
| --- | --- |
| 실행 방식 | `fixedDelay = 1분` |
| 잠금 | ShedLock `GitHubSyncStuckJobRecoveryScheduler`, `lockAtMostFor = 50초` |
| 대상 조회 | `status = 'RUNNING' AND updated_at < now() - 5분` |
| 처리 | `RUNNING → RETRY_WAITING`(`retry_count + 1`, `next_retry_at = now + 1분`), 상한 초과면 `FAILED` + `GitHubSyncFailed` |
| 경합 | 워커 결과 저장과 동시 → `WHERE status='RUNNING' AND updated_at = :observed`로 먼저 커밋한 쪽만 |

## 부록

### A. 스케줄러 경합 지도

모든 경합은 세 가지 수단 중 하나로 해결된다: ① 상태 조건부 UPDATE(먼저 커밋한 쪽 승리) ② 유일 제약(중복 생성 차단) ③ 임대·소유 토큰(늦은 결과 폐기). 행 잠금이 필요한 경로는 서비스별 잠금 순서(contest §4.0, study §5.0)를 따른다.

| 스케줄러 | 경합 상대 | 대상 행 | 해결 방식 | 절 |
| --- | --- | --- | --- | --- |
| `OutboxRelay` | 다른 인스턴스 Relay | `outbox_events` | `SKIP LOCKED` | 0.2.1 |
| `OutboxRelay` | contest 결과 소비 | `exam_submissions.status` | `WHERE status = 'ACCEPTED'` (JUDGED·FAILED 보존) | 0.2.1 |
| `DiagnosisExpiryScheduler` | 진단 최종 제출 API | `diagnosis_attempts.status` | `WHERE status = 'IN_PROGRESS'` | 1.1 |
| `DiagnosisExpiryScheduler` | 진단 문항 제출 API | `diagnosis_submissions` | `uk_diagnosis_submissions_attempt_problem_seq` | 1.1 |
| `DiagnosisScoringScheduler` | 진단 결과 이벤트 소비 | `diagnosis_attempts.status` | `WHERE status = 'CLOSED'` | 1.2 |
| `UnlinkedAssetCleanupScheduler` | 문제 등록·회차 API | `problem_images.status` | `PENDING → DELETING` 선점 | 2.1 |
| `JudgeJobExecutor` | 다른 실행기 | `judge_jobs` | `SKIP LOCKED` + 새 `lease_token` | 3.1 |
| `JudgeJobExecutor` | `JudgeLeaseRecoveryScheduler` | `judge_jobs` | `lease_token` 조건부 UPDATE | 3.1·3.2 |
| `JudgeReconciliationScheduler` | 실행기 결과 저장 | `submissions.status` | `WHERE status = :observed AND judge_attempt = :attempt` | 3.4 |
| `RunLockReconciliationScheduler` | Run 정상 해제 | Redis `run:{memberId}` | 소유 토큰 compare-and-delete | 3.5 |
| `ExamLifecycleScheduler` | 수정·취소·입장 API | `exams`, `exam_participants` | 부모 → 참가자 잠금 + 서버 시각 재검증 | 4.1 |
| `ExamAutoSubmissionScheduler` | 직접 제출·수동 종료 | `exam_submissions` | `(participant_id, problem_id, draft_seq)` 유일 + 완료 표식 | 4.2 |
| `ExamResultFinalizationScheduler` | 채점 소비·재처리 API | `exams`, 결과 | 부모 잠금, 미종결·미확인 작업 0건 조건 | 4.3 |
| `ContestLifecycleScheduler` | 제출·취소 API | `contests` | 잠금 안 서버 시각, `<= ends_at` 접수 / `ends_at < now` 종료 | 4.4 |
| `ContestResultFinalizationScheduler` | 지연 채점·Redis | `contests`, 결과 | 부모 잠금, DB 원천 | 4.5 |
| `ContestSubmissionReconciliationScheduler` | 결과 이벤트 소비 | 접수·결과 | 회차 비교 + `processed_events` | 4.6 |
| `ContestOperationReconciliationScheduler` | 목표 결과·새 prepare | 관리자 작업·가드 | 회차 비교, `operationId`·`sourceVersion` | 4.7 |
| `ContestCacheRebuildScheduler` | 채점·확정 갱신 | Redis 순위 키 | `result_revision` Lua 원자 교체 | 4.8 |
| `StudyApplicationExpiryScheduler` | 승인 API | `study_memberships.status` | 조건부 UPDATE(`PENDING`), 승인은 `expires_at > now()` | 5.1 |
| `StudyApplicationExpiryScheduler` | 신청 취소 API · 탈퇴 이벤트 · 스터디 종료 | `study_memberships.status` | 조건부 UPDATE, 먼저 커밋 승리 | 5.1 |
| `AssignmentLifecycleScheduler` | `SubmissionJudged` 반영 | `assignment_completions` | 문제집 행 선잠금(`FOR SHARE`) + 잠금 읽기 + 유일 제약 upsert, 유형은 접수 시각 함수 | 5.2 |
| `AssignmentLifecycleScheduler` | 가입 승인·즉시 가입 | `assignment_member_statuses`, `assignment_completions` | 잠금 순서 규칙(5.0) + 유일 제약 upsert | 5.2 |
| `AssignmentLifecycleScheduler` | 스터디 종료 API | `assignments.status` | 조건부 UPDATE(`status`) | 5.2 |
| `AssignmentLifecycleScheduler` | 마감 직후 AC 이벤트 | `assignment_member_statuses.status` | 이벤트 경로가 `INCOMPLETE → COMPLETED` 복귀 | 5.2 |
| `AssignmentDeadlineReminderScheduler` | `AssignmentLifecycleScheduler` | `assignments` | `status = 'OPEN'` + `reminder_sent_at IS NULL` 조건부 UPDATE | 5.3 |
| `RecommendationRefreshScheduler` | 프로필 이벤트·조회 시 재계산 | `study_recommendations` | 회원 단위 교체 트랜잭션, 나중 커밋이 최종 | 5.4 |
| `HintGenerationTimeoutScheduler` | LLM 응답 완료 | `hint_requests.status` | 조건부 UPDATE(`GENERATING`) | 6.1 |
| `HintBlockReplicaCleanupScheduler` | 종료 이벤트 처리 | `hint_block_replicas` | 멱등 삭제, 시각 판정 | 6.2 |
| `ExamReminderDispatchScheduler` | `ExamUpdated`·`ExamCanceled` 소비 | `notification_reminders` | `WHERE status = 'SCHEDULED'` | 7.1 |
| `GitHubSyncJobWorker` | 연동 해제·탈퇴 | `github_sync_jobs` | `WHERE status = 'RUNNING'` 결과 저장, 대기 작업 `CANCELED` | 8.1 |
| `GitHubSyncStuckJobRecoveryScheduler` | 워커 결과 저장 | `github_sync_jobs` | `WHERE status='RUNNING' AND updated_at = :observed` | 8.2 |

---

end.
