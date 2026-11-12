package com.backtoback.judge.judging.store;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.DiagnosisSubmissionJudgedPayload;
import com.backtoback.judge.judging.dto.ExamSubmissionJudgedPayload;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.judging.repository.ExecutionRuntimeRepository;
import com.backtoback.judge.judging.service.SubmissionVerdict;
import com.backtoback.judge.judging.service.dto.SubmissionExecutionInput;
import com.backtoback.judge.judging.service.dto.SubmissionLease;
import com.backtoback.judge.judging.service.dto.SubmissionReconciliationCandidate;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.repository.SubmissionRepository;

import lombok.RequiredArgsConstructor;

/** 짧은 트랜잭션과 현재 임대·회차 검증으로 일반 채점 실행의 중복 반영을 막는다. */
@Repository
@RequiredArgsConstructor
public class SubmissionExecutionStore {
    private static final String TOKEN_RECOVERY = "TOKEN_RECOVERY";
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final String RECONCILIATION_FROM = """
        FROM submissions s LEFT JOIN judge_jobs j
        ON j.submission_id=s.id AND j.judge_attempt=s.judge_attempt
        WHERE s.context IN ('PRACTICE','EXAM','DIAGNOSIS')
        AND s.status IN ('QUEUED','JUDGING','RETRY_WAITING') AND s.updated_at<?
        AND (j.id IS NULL OR j.status IN ('SUCCEEDED','FAILED')
            OR (s.status IN ('JUDGING','RETRY_WAITING') AND j.status IN ('PENDING','RETRY_WAITING')))
        """;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SubmissionRepository submissions;
    private final OutboxEventWriter events;
    private final ExecutionRuntimeRepository runtimes;

    /**
     * 오래된 제출 중 최신 작업의 상태가 다른 후보를 잠금 없이 제한 조회한다. (스케줄러 §3.4)
     *
     * @param afterSubmissionId 마지막으로 확인한 제출 ID, 처음부터 조회하면 0
     * @return 지정 ID 뒤의 최대 100개 상태 대사 후보
     * @throws QueryTimeoutException DB 조회 제한시간을 초과한 경우
     */
    @Transactional(
        readOnly = true,
        timeout = 5
    )
    public List<SubmissionReconciliationCandidate> reconciliationCandidates(long afterSubmissionId) {
        return jdbc
            .query(
                "SELECT s.id,s.judge_attempt,s.status,j.id job_id,j.status job_status,"
                    + "s.problem_id,s.problem_revision_id " + RECONCILIATION_FROM
                    + " AND s.id>? ORDER BY s.id LIMIT 100",
                (row, index) -> new SubmissionReconciliationCandidate(
                    row.getLong("id"),
                    row.getObject("job_id", Long.class),
                    row.getInt("judge_attempt"),
                    row.getString("status"),
                    row.getString("job_status"),
                    row.getLong("problem_id"),
                    row.getLong("problem_revision_id")
                ),
                now().minusMinutes(2),
                afterSubmissionId
            );
    }

    /**
     * 순회 위치와 무관하게 가장 오래된 상태 불일치 제출의 경과 시간을 조회한다.
     *
     * @return 가장 오래된 후보의 경과 초, 후보가 없으면 0
     * @throws QueryTimeoutException DB 조회 제한시간을 초과한 경우
     */
    @Transactional(
        readOnly = true,
        timeout = 5
    )
    public double reconciliationBacklogAgeSeconds() {
        var oldest
            = jdbc
                .queryForObject(
                    "SELECT MIN(s.updated_at) " + RECONCILIATION_FROM,
                    LocalDateTime.class,
                    now().minusMinutes(2)
                );
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now()).toMillis() / 1000.0);
    }

    /**
     * 작업·제출 순서로 잠근 뒤 관측 상태와 회차가 그대로인 제출만 보정한다.
     * 종결 작업의 시도 이력과 기한은 보존한다. (스케줄러 §3.4)
     *
     * @param candidate 잠금 없이 선정한 후보
     * @param data 성공 결과 이벤트의 고정 문제 메타데이터, 성공 보정 외에는 {@code null}
     * @return 현재 상태의 불일치를 보정했는지 여부
     */
    @Transactional(timeout = 5)
    public boolean reconcile(SubmissionReconciliationCandidate candidate, SubmissionJudgingDataResponse data) {
        var jobs = jdbc.queryForList("""
            SELECT * FROM judge_jobs WHERE id=? AND submission_id=? AND judge_attempt=? FOR UPDATE SKIP LOCKED
            """, candidate.jobId(), candidate.submissionId(), candidate.judgeAttempt());
        if (jobs.isEmpty()) {
            return false;
        }
        var job = jobs.getFirst();
        var locked
            = jdbc
                .queryForList(
                    """
                        SELECT id FROM submissions WHERE id=? AND judge_attempt=? AND status=? AND updated_at<?
                        FOR UPDATE SKIP LOCKED
                        """,
                    Long.class,
                    candidate.submissionId(),
                    candidate.judgeAttempt(),
                    candidate.observedStatus(),
                    now().minusMinutes(2)
                );
        if (locked.isEmpty() || !candidate.jobStatus().equals(job.get("status"))) {
            return false;
        }
        var lease
            = new SubmissionLease(
                candidate.jobId(),
                candidate.submissionId(),
                candidate.judgeAttempt(),
                ((Number)job.get("try_count")).intValue(),
                null,
                (LocalDateTime)job.get("deadline_at")
            );
        if ("SUCCEEDED".equals(candidate.jobStatus())) {
            return writeCompleted(lease, data, false);
        }
        if ("FAILED".equals(candidate.jobStatus())) {
            jdbc.update("""
                UPDATE submissions SET status='FAILED',verdict=NULL,updated_at=?
                WHERE id=? AND status=? AND judge_attempt=?
                """, now(), candidate.submissionId(), candidate.observedStatus(), candidate.judgeAttempt());
            appendFailure(lease, (String)job.get("last_error"));
            return true;
        }
        return jdbc.update("""
            UPDATE submissions SET status='QUEUED',updated_at=?
            WHERE id=? AND status IN ('JUDGING','RETRY_WAITING') AND judge_attempt=?
            """, now(), candidate.submissionId(), candidate.judgeAttempt()) == 1;
    }

    /**
     * 최신 작업이 없는 제출이 여전히 미종결인지 현재 읽기로 확인한다.
     *
     * @param candidate 작업 누락을 관측한 제출
     * @return 운영 확인이 필요한 누락 상태인지 여부
     * @throws QueryTimeoutException DB 조회 제한시간을 초과한 경우
     */
    @Transactional(
        readOnly = true,
        timeout = 5
    )
    public boolean missingReconciliationJob(SubmissionReconciliationCandidate candidate) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM submissions s WHERE s.id=? AND s.judge_attempt=? AND s.status=?
            AND NOT EXISTS(SELECT 1 FROM judge_jobs j WHERE j.submission_id=s.id AND j.judge_attempt=s.judge_attempt))
            """, Boolean.class, candidate.submissionId(), candidate.judgeAttempt(), candidate.observedStatus()));
    }

    /**
     * 제출 코드와 이미 확정한 실행 환경을 조회한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @return 고정된 실행 입력
     * @throws java.util.NoSuchElementException 제출이 존재하지 않는 경우
     * @throws IllegalArgumentException 일반 풀이에 고정 실행 환경이 없는 경우
     */
    @Transactional(readOnly = true)
    public SubmissionExecutionInput input(SubmissionLease lease) {
        var submission = submissions.findById(lease.submissionId()).orElseThrow();
        var runtime = submission.getRuntime();
        Assert.isTrue(runtime != null || submission.getContext() != Context.PRACTICE, "일반 풀이에는 접수 시 확정한 실행 환경이 필요합니다.");
        return new SubmissionExecutionInput(
            submission.getProblemId(),
            submission.getProblemRevisionId(),
            submission.getLanguage(),
            submission.getSourceCode(),
            runtime == null ? null : runtime.toRuntime()
        );
    }

    /**
     * 현재 작업의 고정 회차 통과 프로필과 테스트 구성을 첫 실행 전에 저장한다.
     *
     * @param lease 현재 작업의 소유권
     * @param data 고정 회차에서 확인한 실행 자료
     * @param language 제출 언어
     * @return 확정한 런타임, 소유권을 잃었으면 빈 값
     * @throws IllegalArgumentException 통과 프로필·스냅샷·언어가 일치하지 않는 경우
     */
    @Transactional
    public Optional<JudgeRuntime> initializeExternal(
        SubmissionLease lease,
        SubmissionJudgingDataResponse data,
        String language
    ) {
        if (!owns(lease)) {
            return Optional.empty();
        }
        var runtime
            = runtimes
                .findSnapshot(data.runtimeKey(language))
                .orElseThrow(() -> new IllegalArgumentException("고정 실행 환경을 찾을 수 없습니다."));
        var selected = runtime.toRuntime();
        Assert.isTrue(language.equals(selected.language()), "고정 실행 환경의 언어가 일치하지 않습니다.");
        var instant = now();
        Assert.state(jdbc.update("""
            UPDATE submissions SET runtime_id=?,updated_at=?
            WHERE id=? AND context<>'PRACTICE' AND runtime_id IS NULL
            """, runtime.getId(), instant, lease.submissionId()) == 1, "미확정 외부 제출만 초기화할 수 있습니다.");
        // 런타임과 테스트를 함께 저장해 초기화 일부만 남은 상태로 재실행되지 않게 한다.
        for (var test : data.cases()) {
            jdbc
                .update(
                    """
                        INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
                            created_at,updated_at) VALUES (?,?,?,?,'NOT_CREATED',?,?)
                        """,
                    lease.jobId(),
                    Long.parseLong(test.testAssetId()),
                    test.testOrder(),
                    test.isSample(),
                    instant,
                    instant
                );
        }
        return Optional.of(selected);
    }

    /**
     * 저장된 테스트 ID·순서·공개 여부와 실행 자료를 대조한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @param data 고정 회차에서 조회한 채점 자료
     * @throws IllegalArgumentException 접수 시 고정한 테스트 구성과 다른 경우
     */
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
        Assert.isTrue(fixed.equals(received), "저장된 테스트 구성이 일치하지 않습니다.");
    }

    /**
     * 경쟁 실행기의 잠금을 기다리지 않고 현재 제출 회차의 작업을 선점한다. (정책 및 상태 §3.4)
     *
     * @return 선점한 임대, 실행 가능한 작업이 없으면 빈 값
     */
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
                        AND j.try_count<=3 AND j.judge_attempt=s.judge_attempt
                        AND s.context IN ('PRACTICE','EXAM','DIAGNOSIS')
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
            UPDATE judge_jobs SET status='RUNNING',lease_token=?,lease_until=?,
                started_at=CASE WHEN last_error=? THEN started_at ELSE ? END,updated_at=?
            WHERE id=?
            """, lease.token(), instant.plusSeconds(120), TOKEN_RECOVERY, instant, instant, lease.jobId());
        jdbc.update("""
            UPDATE submissions SET status='JUDGING',verdict=NULL,compile_message=NULL,updated_at=? WHERE id=?
            """, instant, lease.submissionId());
        return Optional.of(lease);
    }

    /**
     * 현재 시도 결과를 초기화하고 회수한 작업의 확인된 토큰은 재사용한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @return 현재 임대로 새 실행 또는 토큰 재조회를 준비했는지 여부
     */
    @Transactional
    public boolean prepare(SubmissionLease lease) {
        if (!owns(lease)) {
            return false;
        }
        String lastError
            = jdbc.queryForObject("SELECT last_error FROM judge_jobs WHERE id=?", String.class, lease.jobId());
        if ("LEASE_EXPIRED".equals(lastError)) {
            // 임대 회수에서 정한 유예를 지난 UNKNOWN만 새 시도로 넘긴다.
            jdbc.update("""
                UPDATE judge_test_results SET token_status='NOT_CREATED',created_try_count=NULL,updated_at=?
                WHERE judge_job_id=? AND token_status='UNKNOWN'
                """, now(), lease.jobId());
        }
        // 시스템 실패 결과만 다시 실행한다. 발급된 토큰과 확정 판정은 그대로 둔다.
        jdbc.update("""
            UPDATE judge_test_results SET token_status='NOT_CREATED',judge0_token=NULL,created_try_count=NULL,
            verdict=NULL,time_ms=NULL,memory_kb=NULL,actual_output=NULL,updated_at=?
            WHERE judge_job_id=? AND verdict='SYSTEM_ERROR'
            """, now(), lease.jobId());
        return true;
    }

    /**
     * 회수한 작업에서 이미 생성된 Judge0 토큰을 조회한다.
     *
     * @param lease 새 임대와 현재 실행 시도
     * @return 테스트 순서별로 다시 조회할 기존 토큰
     */
    @Transactional(readOnly = true)
    public Map<Integer, String> resumableTokens(SubmissionLease lease) {
        return jdbc.query("""
            SELECT test_order,judge0_token FROM judge_test_results
            WHERE judge_job_id=? AND token_status='CREATED'
            """, rows -> {
            var tokens = new LinkedHashMap<Integer, String>();
            while (rows.next()) {
                tokens.put(rows.getInt("test_order"), rows.getString("judge0_token"));
            }
            return tokens;
        }, lease.jobId());
    }

    /**
     * 현재 채점 회차에서 코드 판정이 이미 확정된 테스트를 조회한다.
     *
     * @param lease 조회할 채점 작업의 임대
     * @return 재실행하지 않을 테스트 순서
     */
    @Transactional(readOnly = true)
    public Set<Integer> completedOrders(SubmissionLease lease) {
        return Set.copyOf(jdbc.queryForList("""
            SELECT test_order FROM judge_test_results
            WHERE judge_job_id=? AND token_status='DONE' AND verdict<>'SYSTEM_ERROR'
            """, Integer.class, lease.jobId()));
    }

    /**
     * 외부 실행 전에 현재 작업 소유권과 임대·처리 기한을 확인한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @return 현재 소유권과 기한이 유효한지 여부
     */
    @Transactional
    public boolean active(SubmissionLease lease) {
        return owns(lease);
    }

    /**
     * 만료된 임대를 부활시키지 않고 현재 소유권의 임대만 연장한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @return 임대를 연장했는지 여부
     */
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

    /**
     * 생성 요청 전에 {@code UNKNOWN}을 저장하여 응답 유실 시 중복 실행을 방지한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @param order 실행할 테스트 순서
     * @return 현재 시도의 생성 시작을 기록했는지 여부
     */
    @Transactional
    public boolean beginCreate(SubmissionLease lease, int order) {
        return owns(lease) && jdbc.update("""
            UPDATE judge_test_results SET token_status='UNKNOWN',created_try_count=?,updated_at=?
            WHERE judge_job_id=? AND test_order=? AND token_status='NOT_CREATED'
            """, lease.tryCount(), now(), lease.jobId(), order) == 1;
    }

    /**
     * 확정 거부된 현재 생성 요청을 되돌리고 시스템 실패 재시도를 함께 기록한다.
     *
     * @param lease 현재 작업 소유권과 실행 시도
     * @param order 거부된 테스트 순서
     * @return 현재 요청을 되돌리고 실패 처리를 완료했는지 여부
     */
    @Transactional
    public boolean rejectCreate(SubmissionLease lease, int order) {
        if (!owns(lease) || jdbc.update("""
            UPDATE judge_test_results SET token_status='NOT_CREATED',created_try_count=NULL,updated_at=?
            WHERE judge_job_id=? AND test_order=? AND created_try_count=? AND token_status='UNKNOWN'
            """, now(), lease.jobId(), order, lease.tryCount()) != 1) {
            return false;
        }
        return fail(lease, "JUDGE0_UNAVAILABLE");
    }

    /**
     * 현재 임대·시도에 속한 생성 응답만 저장한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @param order 생성한 테스트 순서
     * @param token Judge0가 발급한 실행 토큰
     * @return 현재 생성 요청의 토큰을 저장했는지 여부
     * @throws IllegalArgumentException 토큰이 비어 있거나 64자를 초과하는 경우
     */
    @Transactional
    public boolean saveToken(SubmissionLease lease, int order, String token) {
        Assert.isTrue(token != null && !token.isBlank() && token.length() <= 64, "실행 토큰이 올바르지 않습니다.");
        return owns(lease) && jdbc.update("""
            UPDATE judge_test_results SET judge0_token=?,token_status='CREATED',updated_at=?
            WHERE judge_job_id=? AND test_order=? AND created_try_count=? AND token_status='UNKNOWN'
            """, token, now(), lease.jobId(), order, lease.tryCount()) == 1;
    }

    /**
     * 숨김 테스트 출력은 제외하고 현재 시도의 완료 결과를 저장한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @param order 완료한 테스트 순서
     * @param token 조회에 사용한 Judge0 실행 토큰
     * @param result 평가가 완료된 테스트 결과
     * @return 현재 생성 요청의 결과를 저장했는지 여부
     * @throws IllegalArgumentException 완료 판정이 없는 경우
     * @throws ArithmeticException 메모리 사용량이 정수 저장 범위를 초과하는 경우
     */
    @Transactional
    public boolean saveResult(SubmissionLease lease, int order, String token, JudgeTestResult result) {
        Assert.notNull(result.verdict(), "완료 판정이 필요합니다.");
        Integer memory
            = result.memoryKiB() == null ? null
                : result.memoryKiB().setScale(0, java.math.RoundingMode.CEILING).intValueExact();
        boolean saved
            = owns(lease) && jdbc
                .update(
                    """
                        UPDATE judge_test_results SET token_status='DONE',verdict=?,time_ms=?,memory_kb=?,
                        actual_output=CASE WHEN is_sample THEN ? ELSE NULL END,updated_at=?
                        WHERE judge_job_id=? AND test_order=?
                        AND BINARY judge0_token=BINARY ? AND token_status='CREATED'
                        """,
                    result.verdict().name(),
                    result.timeMs(),
                    memory,
                    result.actualReturnText(),
                    now(),
                    lease.jobId(),
                    order,
                    token
                ) == 1;
        if (saved && result.verdict() == JudgeVerdict.CE && result.compileMessage() != null) {
            jdbc.update("""
                UPDATE judge_jobs SET pending_compile_message=COALESCE(pending_compile_message,?) WHERE id=?
                """, result.compileMessage(), lease.jobId());
        }
        return saved;
    }

    /**
     * 전체 결과·제출 요약·시도 이력·Outbox를 같은 트랜잭션에서 확정한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @param data 결과 이벤트에 포함할 고정 문제 메타데이터
     * @return 현재 소유권으로 전체 결과를 확정했는지 여부
     * @throws IllegalArgumentException 미완료 또는 시스템 오류 테스트가 남아 있는 경우
     */
    @Transactional
    public boolean complete(SubmissionLease lease, SubmissionJudgingDataResponse data) {
        if (!owns(lease)) {
            return false;
        }
        return writeCompleted(lease, data, true);
    }

    /**
     * 정상 실행과 상태 대사가 완료 결과 집계·이벤트 저장을 공유한다.
     *
     * @param lease 잠근 작업의 현재 회차
     * @param data 고정 문제 메타데이터
     * @param recordExecution 정상 실행이면 시도 이력과 작업 종결도 기록할지 여부
     * @return 완료 결과를 저장했는지 여부
     */
    private boolean writeCompleted(SubmissionLease lease, SubmissionJudgingDataResponse data, boolean recordExecution) {
        String correlationId = recordExecution ? lease.correlationId() : MDC.get("correlationId");
        if (correlationId == null) {
            correlationId = "sched-JudgeReconciliation-" + UUID.randomUUID();
        }
        var verdicts = jdbc.queryForList("""
            SELECT CASE WHEN token_status='DONE' THEN verdict ELSE NULL END
            FROM judge_test_results WHERE judge_job_id=? ORDER BY test_order
            """, String.class, lease.jobId());
        var verdict
            = SubmissionVerdict
                .summarize(verdicts.stream().map(value -> value == null ? null : JudgeVerdict.valueOf(value)).toList());
        var metrics = jdbc.queryForMap("""
            SELECT SUM(verdict='AC') passed,MAX(time_ms) time,MAX(memory_kb) memory
            FROM judge_test_results WHERE judge_job_id=?
            """, lease.jobId());
        Object time = verdict == JudgeVerdict.CE ? null : metrics.get("time");
        Object memory = verdict == JudgeVerdict.CE ? null : metrics.get("memory");
        if (recordExecution && !owns(lease)) {
            return false;
        }
        var instant
            = recordExecution ? now() : jdbc
                .queryForObject("SELECT finished_at FROM judge_jobs WHERE id=?", LocalDateTime.class, lease.jobId());
        Assert.notNull(instant, "성공 작업의 종결 시각이 필요합니다.");
        jdbc
            .update(
                """
                    UPDATE submissions SET status='COMPLETED',verdict=?,passed_count=?,total_count=?,max_time_ms=?,
                    max_memory_kb=?,compile_message=CASE WHEN ? THEN (
                        SELECT COALESCE(pending_compile_message,submissions.compile_message) FROM judge_jobs WHERE id=?
                    ) ELSE NULL END,
                    judged_at=?,updated_at=? WHERE id=?
                    """,
                verdict.name(),
                metrics.get("passed"),
                verdicts.size(),
                time,
                memory,
                verdict == JudgeVerdict.CE,
                lease.jobId(),
                instant,
                now(),
                lease.submissionId()
            );
        if (recordExecution) {
            recordRun(lease, "SUCCEEDED", null, instant);
            jdbc.update("""
                UPDATE judge_jobs SET status='SUCCEEDED',finished_at=?,lease_token=NULL,lease_until=NULL,
                last_error=NULL,pending_compile_message=NULL,updated_at=? WHERE id=?
                """, instant, instant, lease.jobId());
        }
        var submission = submissions.findById(lease.submissionId()).orElseThrow();
        if (submission.getContext() == Context.EXAM) {
            var examResult
                = new ExamSubmissionJudgedPayload(
                    Context.EXAM.name(),
                    submission.getContextId().toString(),
                    submission.getSourceSubmissionId().toString(),
                    submission.getId().toString(),
                    Long.toString(submission.getMemberId()),
                    Long.toString(submission.getProblemId()),
                    Long.toString(submission.getProblemRevisionId()),
                    lease.judgeAttempt(),
                    Submission.Status.COMPLETED.name(),
                    verdict.name(),
                    ((Number)metrics.get("passed")).intValue(),
                    verdicts.size(),
                    time == null ? null : ((Number)time).intValue(),
                    memory == null ? null : ((Number)memory).intValue(),
                    EVENT_TIME.format(submission.getSubmittedAt().atZone(ClockConfiguration.SERVICE_ZONE))
                );
            events
                .append(
                    "Submission",
                    "ExamSubmissionJudged",
                    "v1",
                    Long.toString(lease.submissionId()),
                    correlationId,
                    examResult
                );
            return true;
        }
        if (submission.getContext() == Context.DIAGNOSIS) {
            var diagnosisResult
                = new DiagnosisSubmissionJudgedPayload(
                    Context.DIAGNOSIS.name(),
                    submission.getContextId().toString(),
                    submission.getSourceSubmissionId().toString(),
                    submission.getId().toString(),
                    Long.toString(submission.getMemberId()),
                    Long.toString(submission.getProblemId()),
                    Long.toString(submission.getProblemRevisionId()),
                    lease.judgeAttempt(),
                    Submission.Status.COMPLETED.name(),
                    verdict.name(),
                    ((Number)metrics.get("passed")).intValue(),
                    verdicts.size(),
                    time == null ? null : ((Number)time).intValue(),
                    memory == null ? null : ((Number)memory).intValue(),
                    EVENT_TIME.format(submission.getSubmittedAt().atZone(ClockConfiguration.SERVICE_ZONE))
                );
            events
                .append(
                    "Submission",
                    "DiagnosisSubmissionJudged",
                    "v1",
                    Long.toString(lease.submissionId()),
                    correlationId,
                    diagnosisResult
                );
            return true;
        }
        var payload = payload(lease, submission);
        payload.put("verdict", verdict.name());
        payload.put("passedCount", metrics.get("passed"));
        payload.put("totalCount", verdicts.size());
        payload.put("maxTimeMs", time);
        payload.put("maxMemoryKb", memory);
        String eventType = switch (submission.getContext()) {
            case PRACTICE -> {
                payload.put("language", submission.getLanguage());
                payload.put("judgedAt", instant.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime().toString());
                payload.put("problemTitle", data.problemTitle());
                payload.put("problemDifficulty", data.problemDifficulty());
                payload.put("problemTags", data.problemTags());
                payload.put("problemTagIds", data.problemTagIds());
                yield "SubmissionJudged";
            }
            case EXAM, DIAGNOSIS, CONTEST -> throw new IllegalStateException("결과 발행을 지원하지 않는 채점 문맥입니다.");
        };
        events.append("Submission", eventType, "v1", Long.toString(lease.submissionId()), correlationId, payload);
        return true;
    }

    /**
     * 서킷 차단으로 Judge0를 호출하지 못한 선점을 같은 회차·시도에 반환한다. (스케줄러 §3.1)
     *
     * @param lease 아직 실행하지 않은 현재 임대
     * @return 기한 내 현재 임대를 반환했는지 여부
     */
    @Transactional
    public boolean releaseUncalled(SubmissionLease lease) {
        if (!owns(lease)) {
            return false;
        }
        var instant = now();
        jdbc.update("""
            UPDATE judge_jobs SET status='RETRY_WAITING',lease_token=NULL,lease_until=NULL,next_run_at=?,updated_at=?
            WHERE id=?
            """, instant, instant, lease.jobId());
        jdbc.update("UPDATE submissions SET status='QUEUED',updated_at=? WHERE id=?", instant, lease.submissionId());
        return true;
    }

    /**
     * 불명확한 실행은 보존하고 확정한 시스템 실패만 재시도하거나 종결한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @param errorCode 원문을 포함하지 않는 시스템 실패 코드
     * @return 현재 소유권으로 실패 처리를 반영했는지 여부
     */
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
                UPDATE judge_jobs SET status='RETRY_WAITING',try_count=try_count+1,unknown_retry_count=0,next_run_at=?,
                lease_token=NULL,lease_until=NULL,last_error=?,updated_at=? WHERE id=?
                """, instant.plusSeconds(lease.tryCount() == 1 ? 10 : 30), errorCode, instant, lease.jobId());
            jdbc
                .update(
                    "UPDATE submissions SET status='QUEUED',updated_at=? WHERE id=?",
                    instant,
                    lease.submissionId()
                );
        } else {
            terminalFailure(lease, errorCode, instant);
        }
        return true;
    }

    /**
     * 30초 이상 응답이 불명확한 요청을 회수한다. 실행 중에는 현재 시도의 요청만 회수한다. (스케줄러 §3.3)
     *
     * @return 처리할 UNKNOWN 요청이 있었는지 여부
     */
    @Transactional
    public boolean recoverUnknownOne() {
        var observedAt = now();
        var leases
            = jdbc
                .query(
                    """
                        SELECT j.id,j.submission_id,j.judge_attempt,j.try_count,j.lease_token,j.deadline_at
                        FROM judge_jobs j STRAIGHT_JOIN submissions s ON s.id=j.submission_id
                        WHERE s.context IN ('PRACTICE','EXAM','DIAGNOSIS')
                        AND ((s.judge_attempt=j.judge_attempt AND j.status='RUNNING'
                            AND j.lease_until>? AND j.deadline_at>?)
                            OR j.status IN ('RETRY_WAITING','FAILED','SUCCEEDED'))
                        AND EXISTS (
                            SELECT 1 FROM judge_test_results t WHERE t.judge_job_id=j.id
                            AND t.token_status='UNKNOWN' AND t.updated_at<=?
                            AND (j.status<>'RUNNING' OR t.created_try_count=j.try_count)
                        )
                        ORDER BY j.id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """,
                    (row, index) -> new SubmissionLease(
                        row.getLong("id"),
                        row.getLong("submission_id"),
                        row.getInt("judge_attempt"),
                        row.getInt("try_count"),
                        row.getString("lease_token"),
                        row.getObject("deadline_at", LocalDateTime.class)
                    ),
                    observedAt,
                    observedAt,
                    observedAt.minusSeconds(30)
                );
        if (leases.isEmpty()) {
            return false;
        }
        var lease = leases.getFirst();
        String status = jdbc.queryForObject("SELECT status FROM judge_jobs WHERE id=?", String.class, lease.jobId());
        boolean running = "RUNNING".equals(status);
        if (running && !owns(lease)) {
            return false;
        }
        var instant = now();
        int reset = jdbc.update("""
            UPDATE judge_test_results SET token_status='NOT_CREATED',created_try_count=NULL,updated_at=?
            WHERE judge_job_id=? AND token_status='UNKNOWN' AND updated_at<=?
            AND (?<>'RUNNING' OR created_try_count=?)
            """, instant, lease.jobId(), instant.minusSeconds(30), status, lease.tryCount());
        if (reset == 0 || !running) {
            return reset > 0;
        }
        int retries = jdbc.queryForObject("""
            SELECT unknown_retry_count FROM judge_jobs WHERE id=?
            """, Integer.class, lease.jobId());
        if (retries == 2) {
            return fail(lease, "JUDGE0_TIMEOUT");
        }
        // 대기 중에는 쓰기 권한이 없다. 재선점 전 기한이 끝날 경우를 위해 이전 임대 토큰만 보존한다.
        jdbc.update("""
            UPDATE judge_jobs SET status='RETRY_WAITING',next_run_at=?,lease_until=NULL,
            unknown_retry_count=unknown_retry_count+1,
            last_error=?,updated_at=? WHERE id=?
            """, instant, TOKEN_RECOVERY, instant, lease.jobId());
        jdbc.update("UPDATE submissions SET status='QUEUED',updated_at=? WHERE id=?", instant, lease.submissionId());
        return true;
    }

    /**
     * 기한이 지났거나 임대가 만료된 제출 작업 한 건을 회수한다. (스케줄러 §3.2)
     *
     * @return 기한 초과 종결 또는 임대 회수를 반영했는지 여부
     */
    @Transactional
    public boolean recoverOne() {
        var observedAt = now();
        var rows
            = jdbc
                .query(
                    """
                        SELECT j.* FROM judge_jobs j STRAIGHT_JOIN submissions s ON s.id=j.submission_id
                        WHERE s.context IN ('PRACTICE','EXAM','DIAGNOSIS') AND s.judge_attempt=j.judge_attempt
                        AND j.status IN ('PENDING','RUNNING','RETRY_WAITING')
                        AND (j.deadline_at<=? OR (j.status='RUNNING' AND (j.lease_until<=? OR j.started_at<=?)))
                        ORDER BY j.deadline_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """,
                    (row, index) -> new SubmissionLease(
                        row.getLong("id"),
                        row.getLong("submission_id"),
                        row.getInt("judge_attempt"),
                        row.getInt("try_count"),
                        row.getString("lease_token"),
                        row.getObject("deadline_at", LocalDateTime.class)
                    ),
                    observedAt,
                    observedAt,
                    observedAt.minusMinutes(10)
                );
        if (rows.isEmpty()) {
            return false;
        }
        var lease = rows.getFirst();
        var instant = now();
        if (!instant.isBefore(lease.deadlineAt())) {
            // 진행 중이거나 같은 시도의 UNKNOWN 복구를 기다리는 경우만 실행 이력을 마무리한다.
            if (lease.token() != null) {
                recordRun(lease, "SYSTEM_ERROR", "DEADLINE_EXCEEDED", instant);
            }
            terminalFailure(lease, "DEADLINE_EXCEEDED", instant);
        } else {
            recoverExpiredLease(lease, instant);
        }
        return true;
    }

    /**
     * 잠근 임대를 닫고 시도 상한 안에서만 다음 시도를 예약한다.
     *
     * @param lease 회수할 작업의 관측한 임대
     * @param instant 잠금 획득 후 시각
     */
    private void recoverExpiredLease(SubmissionLease lease, LocalDateTime instant) {
        recordRun(lease, "LEASE_EXPIRED", "LEASE_EXPIRED", instant);
        if (lease.tryCount() >= 3) {
            terminalFailure(lease, "LEASE_EXHAUSTED", instant);
            return;
        }
        // 생성 여부를 모르는 요청은 스케줄러 §3.3의 30초 대기 후에만 다시 생성한다.
        jdbc.update("""
            UPDATE judge_jobs SET status='RETRY_WAITING',try_count=try_count+1,unknown_retry_count=0,
            next_run_at=GREATEST(?,COALESCE(
                (SELECT MAX(updated_at) + INTERVAL 30 SECOND FROM judge_test_results
                 WHERE judge_job_id=? AND token_status='UNKNOWN'),?)),
            lease_token=NULL,lease_until=NULL,last_error='LEASE_EXPIRED',updated_at=? WHERE id=?
            """, instant, lease.jobId(), instant, instant, lease.jobId());
        jdbc.update("UPDATE submissions SET status='QUEUED',updated_at=? WHERE id=?", instant, lease.submissionId());
    }

    /**
     * 현재 실행 시도의 종료 이력을 호출 트랜잭션에 기록한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @param result 실행 시도의 종료 상태
     * @param error 시스템 오류 코드, 정상 종료이면 null
     * @param instant 종료 처리 시각
     */
    private void recordRun(SubmissionLease lease, String result, String error, LocalDateTime instant) {
        jdbc.update("""
            INSERT INTO judge_job_runs(judge_job_id,try_count,lease_token,result,error_message,
            started_at,finished_at,created_at)
            SELECT id,try_count,lease_token,?,?,started_at,?,? FROM judge_jobs WHERE id=?
            """, result, error, instant, instant, lease.jobId());
    }

    /**
     * 작업·제출의 실패 상태와 실패 Outbox를 호출 트랜잭션에 기록한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @param error 종결 사유인 시스템 오류 코드
     * @param instant 종결 처리 시각
     */
    private void terminalFailure(SubmissionLease lease, String error, LocalDateTime instant) {
        jdbc.update("""
            UPDATE judge_jobs SET status='FAILED',finished_at=?,last_error=?,lease_token=NULL,lease_until=NULL,
            pending_compile_message=NULL,updated_at=? WHERE id=?
            """, instant, error, instant, lease.jobId());
        jdbc
            .update(
                "UPDATE submissions SET status='FAILED',verdict=NULL,updated_at=? WHERE id=?",
                instant,
                lease.submissionId()
            );
        appendFailure(lease, error);
    }

    /**
     * 이미 잠근 제출의 문맥에 맞는 실패 이벤트를 기록한다.
     *
     * @param lease 실패한 작업의 현재 회차
     * @param error 원문을 포함하지 않는 실패 코드
     */
    private void appendFailure(SubmissionLease lease, String error) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null) {
            correlationId = lease.correlationId();
        }
        var submission = submissions.findById(lease.submissionId()).orElseThrow();
        var payload = payload(lease, submission);
        payload.put("context", submission.getContext().name());
        payload.put("contextId", submission.getContextId() == null ? null : submission.getContextId().toString());
        payload
            .put(
                "receiptId",
                submission.getSourceSubmissionId() == null ? null : submission.getSourceSubmissionId().toString()
            );
        payload.put("status", "FAILED");
        payload.put("errorCode", error);
        events
            .append(
                "Submission",
                "SubmissionFailed",
                "v1",
                Long.toString(lease.submissionId()),
                correlationId,
                payload
            );
    }

    /**
     * 고정 제출 정보로 결과 이벤트의 공통 필드를 구성한다.
     *
     * @param lease 현재 작업의 채점 회차
     * @param submission 제출의 불변 식별 정보와 원본 접수 시각
     * @return 제출 식별자와 고정 회차를 담은 이벤트 필드
     */
    private LinkedHashMap<String, Object> payload(SubmissionLease lease, Submission submission) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("submissionId", submission.getId().toString());
        payload.put("userId", Long.toString(submission.getMemberId()));
        payload.put("problemId", Long.toString(submission.getProblemId()));
        payload.put("problemRevisionId", Long.toString(submission.getProblemRevisionId()));
        payload.put("judgeAttempt", lease.judgeAttempt());
        var submittedAt = submission.getSubmittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
        payload
            .put(
                "submittedAt",
                submission.getContext() == Context.PRACTICE ? submittedAt.toString() : EVENT_TIME.format(submittedAt)
            );
        return payload;
    }

    /**
     * 작업과 제출을 순서대로 잠그고 현재 회차·시도·임대·기한을 확인한다.
     * 활성 트랜잭션 안에서 호출하며, 획득한 행 잠금은 호출 트랜잭션이 끝날 때까지 유지한다.
     *
     * @param lease 현재 작업의 회차·실행 시도·임대 소유권
     * @return 현재 작업의 실행 권한이 유효한지 여부
     */
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

    /**
     * 공통 시각을 DB 저장 정밀도인 마이크로초로 정규화한다.
     *
     * @return 서비스 시간대의 현재 시각
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
    }
}
