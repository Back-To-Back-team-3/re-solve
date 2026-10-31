package com.backtoback.judge.judging.store;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.judging.service.SubmissionVerdict;
import com.backtoback.judge.judging.service.dto.SubmissionExecutionInput;
import com.backtoback.judge.judging.service.dto.SubmissionLease;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.submission.repository.SubmissionRepository;

import lombok.RequiredArgsConstructor;

/** 짧은 트랜잭션과 현재 임대·회차 검증으로 일반 채점 실행의 중복 반영을 막는다. */
@Repository
@RequiredArgsConstructor
public class SubmissionExecutionStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SubmissionRepository submissions;
    private final OutboxEventWriter events;

    @Transactional(readOnly = true)
    public SubmissionExecutionInput input(SubmissionLease lease) {
        var submission = submissions.findById(lease.submissionId()).orElseThrow();
        Assert.notNull(submission.getRuntime(), "접수 시 확정한 실행 환경이 필요합니다.");
        return new SubmissionExecutionInput(
            submission.getProblemId(),
            submission.getProblemRevisionId(),
            submission.getLanguage(),
            submission.getSourceCode(),
            submission.getRuntime().toRuntime()
        );
    }

    /** 접수 시 확보한 불변 테스트 ID·순서·공개 여부와 실행 자료를 대조한다. */
    @Transactional(readOnly = true)
    public void validateCases(SubmissionLease lease, SubmissionJudgingDataResponse data) {
        var fixed = jdbc.query("""
            SELECT test_asset_id,test_order,is_sample FROM judge_test_results WHERE judge_job_id=? ORDER BY test_order
            """, (row, index) -> List.of(row.getString(1), row.getInt(2), row.getBoolean(3)), lease.jobId());
        var received
            = data
                .cases()
                .stream()
                .sorted(java.util.Comparator.comparingInt(SubmissionJudgingDataResponse.TestCase::testOrder))
                .map(test -> List.of(test.testAssetId(), test.testOrder(), test.isSample()))
                .toList();
        Assert.isTrue(fixed.equals(received), "접수 시 고정한 테스트 구성이 일치하지 않습니다.");
    }

    /** 경쟁 실행기의 잠금을 기다리지 않고 현재 제출 회차의 작업을 선점한다. (정책 및 상태 §3.4) */
    @Transactional
    public Optional<SubmissionLease> claim() {
        var instant = now();
        var candidates
            = jdbc
                .query(
                    """
                        SELECT j.id,j.submission_id,j.judge_attempt,j.try_count,j.deadline_at
                        FROM judge_jobs j STRAIGHT_JOIN submissions s ON s.id=j.submission_id
                        WHERE j.status IN ('PENDING','RETRY_WAITING') AND j.next_run_at<=? AND j.deadline_at>?
                        AND j.try_count<=3 AND j.judge_attempt=s.judge_attempt AND s.context='PRACTICE'
                        ORDER BY j.next_run_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """,
                    (row, index) -> new SubmissionLease(
                        row.getLong("id"),
                        row.getLong("submission_id"),
                        row.getInt("judge_attempt"),
                        row.getInt("try_count"),
                        UUID.randomUUID().toString(),
                        row.getTimestamp("deadline_at").toLocalDateTime()
                    ),
                    instant,
                    instant
                );
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var lease = candidates.getFirst();
        instant = now();
        if (!instant.isBefore(lease.deadlineAt())) {
            return Optional.empty();
        }
        jdbc.update("""
            UPDATE judge_jobs SET status='RUNNING',lease_token=?,lease_until=?,started_at=?,updated_at=?
            WHERE id=?
            """, lease.token(), instant.plusSeconds(120), instant, instant, lease.jobId());
        jdbc
            .update(
                "UPDATE submissions SET status='JUDGING',verdict=NULL,compile_message=NULL,updated_at=? WHERE id=?",
                instant,
                lease.submissionId()
            );
        return Optional.of(lease);
    }

    /** 현재 시도 결과만 사용하도록 결과 자리를 초기화한다. 불명확한 기존 실행은 다시 생성하지 않는다. */
    @Transactional
    public boolean prepare(SubmissionLease lease) {
        if (!owns(lease)) {
            return false;
        }
        Integer unresolved = jdbc.queryForObject("""
            SELECT COUNT(*) FROM judge_test_results WHERE judge_job_id=?
            AND token_status IN ('UNKNOWN','CREATED')
            """, Integer.class, lease.jobId());
        if (unresolved != null && unresolved > 0) {
            return false;
        }
        jdbc.update("""
            UPDATE judge_test_results SET token_status='NOT_CREATED',judge0_token=NULL,created_try_count=NULL,
            verdict=NULL,time_ms=NULL,memory_kb=NULL,actual_output=NULL,updated_at=? WHERE judge_job_id=?
            """, now(), lease.jobId());
        return true;
    }

    /** 외부 실행 전 현재 소유권을 확인한다. */
    @Transactional
    public boolean active(SubmissionLease lease) {
        return owns(lease);
    }

    /** 이미 만료된 임대를 부활시키지 않고 현재 소유권의 임대만 연장한다. */
    @Transactional
    public boolean heartbeat(SubmissionLease lease) {
        if (!owns(lease)) {
            return false;
        }
        var instant = now();
        jdbc
            .update(
                "UPDATE judge_jobs SET lease_until=?,updated_at=? WHERE id=?",
                instant.plusSeconds(120),
                instant,
                lease.jobId()
            );
        return true;
    }

    /** 생성 요청 전에 UNKNOWN을 저장하여 응답 유실 시 중복 실행을 방지한다. */
    @Transactional
    public boolean beginCreate(SubmissionLease lease, int order) {
        return owns(lease) && jdbc.update("""
            UPDATE judge_test_results SET token_status='UNKNOWN',created_try_count=?,updated_at=?
            WHERE judge_job_id=? AND test_order=? AND token_status='NOT_CREATED'
            """, lease.tryCount(), now(), lease.jobId(), order) == 1;
    }

    /** 이전 임대·시도의 늦은 생성 응답은 폐기한다. */
    @Transactional
    public boolean saveToken(SubmissionLease lease, int order, String token) {
        Assert.isTrue(token != null && !token.isBlank() && token.length() <= 64, "실행 토큰이 올바르지 않습니다.");
        return owns(lease) && jdbc.update("""
            UPDATE judge_test_results SET judge0_token=?,token_status='CREATED',updated_at=?
            WHERE judge_job_id=? AND test_order=? AND created_try_count=? AND token_status='UNKNOWN'
            """, token, now(), lease.jobId(), order, lease.tryCount()) == 1;
    }

    /** 숨김 테스트 출력은 저장하지 않으며 현재 시도 결과만 반영한다. */
    @Transactional
    public boolean saveResult(SubmissionLease lease, int order, JudgeTestResult result) {
        Assert.notNull(result.verdict(), "완료 판정이 필요합니다.");
        Integer memory
            = result.memoryKiB() == null ? null
                : result.memoryKiB().setScale(0, java.math.RoundingMode.CEILING).intValueExact();
        return owns(lease) && jdbc
            .update(
                """
                    UPDATE judge_test_results SET token_status='DONE',verdict=?,time_ms=?,memory_kb=?,
                    actual_output=CASE WHEN is_sample THEN ? ELSE NULL END,updated_at=?
                    WHERE judge_job_id=? AND test_order=? AND created_try_count=? AND token_status='CREATED'
                    """,
                result.verdict().name(),
                result.timeMs(),
                memory,
                result.actualReturnText(),
                now(),
                lease.jobId(),
                order,
                lease.tryCount()
            ) == 1;
    }

    /** 전체 결과·제출 요약·시도 이력·Outbox를 같은 트랜잭션에서 확정한다. */
    @Transactional
    public boolean complete(SubmissionLease lease, SubmissionJudgingDataResponse data, String compileMessage) {
        if (!owns(lease)) {
            return false;
        }
        var verdicts = jdbc.queryForList("""
            SELECT CASE WHEN token_status='DONE' AND created_try_count=? THEN verdict ELSE NULL END
            FROM judge_test_results WHERE judge_job_id=? ORDER BY test_order
            """, String.class, lease.tryCount(), lease.jobId());
        var verdict
            = SubmissionVerdict
                .summarize(verdicts.stream().map(value -> value == null ? null : JudgeVerdict.valueOf(value)).toList());
        var metrics = jdbc.queryForMap("""
            SELECT SUM(verdict='AC') passed,MAX(time_ms) time,MAX(memory_kb) memory
            FROM judge_test_results WHERE judge_job_id=?
            """, lease.jobId());
        Object time = verdict == JudgeVerdict.CE ? null : metrics.get("time");
        Object memory = verdict == JudgeVerdict.CE ? null : metrics.get("memory");
        if (!owns(lease)) {
            return false;
        }
        var instant = now();
        jdbc
            .update(
                """
                    UPDATE submissions SET status='COMPLETED',verdict=?,passed_count=?,total_count=?,max_time_ms=?,
                    max_memory_kb=?,compile_message=?,judged_at=?,updated_at=? WHERE id=?
                    """,
                verdict.name(),
                metrics.get("passed"),
                verdicts.size(),
                time,
                memory,
                verdict == JudgeVerdict.CE ? compileMessage : null,
                instant,
                instant,
                lease.submissionId()
            );
        recordRun(lease, "SUCCEEDED", null, instant);
        jdbc.update("""
            UPDATE judge_jobs SET status='SUCCEEDED',finished_at=?,lease_token=NULL,lease_until=NULL,
            last_error=NULL,updated_at=? WHERE id=?
            """, instant, instant, lease.jobId());
        var payload = payload(lease);
        payload.put("verdict", verdict.name());
        payload.put("passedCount", metrics.get("passed"));
        payload.put("totalCount", verdicts.size());
        payload.put("maxTimeMs", time);
        payload.put("maxMemoryKb", memory);
        payload.put("judgedAt", instant.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime().toString());
        payload.put("problemTitle", data.problemTitle());
        payload.put("problemDifficulty", data.problemDifficulty());
        payload.put("problemTags", data.problemTags());
        payload.put("problemTagIds", data.problemTagIds());
        events
            .append(
                "Submission",
                "SubmissionJudged",
                "v1",
                Long.toString(lease.submissionId()),
                lease.correlationId(),
                payload
            );
        return true;
    }

    /** 불명확한 실행은 재생성하지 않고 확정한 시스템 실패만 재시도한다. */
    @Transactional
    public boolean fail(SubmissionLease lease, String errorCode) {
        if (!owns(lease)) {
            return false;
        }
        Integer unresolved = jdbc.queryForObject("""
            SELECT COUNT(*) FROM judge_test_results WHERE judge_job_id=? AND token_status IN ('UNKNOWN','CREATED')
            """, Integer.class, lease.jobId());
        if (unresolved != null && unresolved > 0) {
            return false;
        }
        var instant = now();
        recordRun(lease, "SYSTEM_ERROR", errorCode, instant);
        if (lease.tryCount() < 3) {
            jdbc.update("""
                UPDATE judge_jobs SET status='RETRY_WAITING',try_count=try_count+1,next_run_at=?,
                lease_token=NULL,lease_until=NULL,last_error=?,updated_at=? WHERE id=?
                """, instant.plusSeconds(lease.tryCount() == 1 ? 10 : 30), errorCode, instant, lease.jobId());
            jdbc
                .update(
                    "UPDATE submissions SET status='RETRY_WAITING',updated_at=? WHERE id=?",
                    instant,
                    lease.submissionId()
                );
        } else {
            terminalFailure(lease, errorCode, instant);
        }
        return true;
    }

    /** 실행 전 기한 만료도 가짜 실행 이력 없이 실패 이벤트와 함께 종결한다. */
    @Transactional
    public boolean expireOne() {
        var rows
            = jdbc
                .query(
                    """
                        SELECT j.* FROM judge_jobs j STRAIGHT_JOIN submissions s ON s.id=j.submission_id
                        WHERE j.status IN ('PENDING','RUNNING','RETRY_WAITING') AND j.deadline_at<=?
                        AND s.context='PRACTICE' AND s.judge_attempt=j.judge_attempt
                        ORDER BY j.deadline_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """,
                    (row, index) -> new SubmissionLease(
                        row.getLong("id"),
                        row.getLong("submission_id"),
                        row.getInt("judge_attempt"),
                        row.getInt("try_count"),
                        row.getString("lease_token"),
                        row.getTimestamp("deadline_at").toLocalDateTime()
                    ),
                    now()
                );
        if (rows.isEmpty()) {
            return false;
        }
        var lease = rows.getFirst();
        if (lease.token() != null) {
            recordRun(lease, "SYSTEM_ERROR", "DEADLINE_EXCEEDED", now());
        }
        terminalFailure(lease, "DEADLINE_EXCEEDED", now());
        return true;
    }

    private void recordRun(SubmissionLease lease, String result, String error, LocalDateTime instant) {
        jdbc.update("""
            INSERT INTO judge_job_runs(judge_job_id,try_count,lease_token,result,error_message,
            started_at,finished_at,created_at)
            SELECT id,try_count,lease_token,?,?,started_at,?,? FROM judge_jobs WHERE id=?
            """, result, error, instant, instant, lease.jobId());
    }

    private void terminalFailure(SubmissionLease lease, String error, LocalDateTime instant) {
        jdbc.update("""
            UPDATE judge_jobs SET status='FAILED',finished_at=?,last_error=?,lease_token=NULL,lease_until=NULL,
            updated_at=? WHERE id=?
            """, instant, error, instant, lease.jobId());
        jdbc
            .update(
                "UPDATE submissions SET status='FAILED',verdict=NULL,updated_at=? WHERE id=?",
                instant,
                lease.submissionId()
            );
        var payload = payload(lease);
        payload.remove("language");
        payload.put("context", "PRACTICE");
        payload.put("contextId", null);
        payload.put("receiptId", null);
        payload.put("status", "FAILED");
        payload.put("errorCode", error);
        events
            .append(
                "Submission",
                "SubmissionFailed",
                "v1",
                Long.toString(lease.submissionId()),
                lease.correlationId(),
                payload
            );
    }

    private LinkedHashMap<String, Object> payload(SubmissionLease lease) {
        return jdbc.queryForObject("SELECT * FROM submissions WHERE id=?", (row, index) -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("submissionId", Long.toString(lease.submissionId()));
            value.put("userId", row.getString("member_id"));
            value.put("problemId", row.getString("problem_id"));
            value.put("problemRevisionId", row.getString("problem_revision_id"));
            value.put("judgeAttempt", lease.judgeAttempt());
            value.put("language", row.getString("language"));
            value
                .put(
                    "submittedAt",
                    row
                        .getTimestamp("submitted_at")
                        .toLocalDateTime()
                        .atZone(ClockConfiguration.SERVICE_ZONE)
                        .toOffsetDateTime()
                        .toString()
                );
            return value;
        }, lease.submissionId());
    }

    private boolean owns(SubmissionLease lease) {
        // 선점과 동일하게 작업→제출 순서로 잠가 교착 순환을 피한다.
        if (jdbc.queryForList("SELECT id FROM judge_jobs WHERE id=? FOR UPDATE", Long.class, lease.jobId()).isEmpty()) {
            return false;
        }
        var attempts
            = jdbc
                .queryForList(
                    "SELECT judge_attempt FROM submissions WHERE id=? FOR UPDATE",
                    Integer.class,
                    lease.submissionId()
                );
        if (attempts.isEmpty() || attempts.getFirst() != lease.judgeAttempt()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.query("""
            SELECT status,lease_token,try_count,lease_until,deadline_at FROM judge_jobs WHERE id=? FOR UPDATE
            """, row -> {
            if (!row.next()) {
                return false;
            }
            var instant = now();
            return "RUNNING".equals(row.getString("status")) && lease.token().equals(row.getString("lease_token"))
                && lease.tryCount() == row.getInt("try_count")
                && instant.isBefore(row.getTimestamp("lease_until").toLocalDateTime())
                && instant.isBefore(row.getTimestamp("deadline_at").toLocalDateTime());
        }, lease.jobId()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
    }
}
