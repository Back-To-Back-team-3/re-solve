package com.backtoback.judge.judging.store;

import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.ExecutionRuntime;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.dto.ProblemValidationRequest;
import com.backtoback.judge.judging.repository.ExecutionRuntimeRepository;
import com.backtoback.judge.judging.service.dto.ValidationLease;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;

import lombok.RequiredArgsConstructor;

/** 짧은 DB 트랜잭션으로 검증 선점·소유권·종결과 Outbox 원자성을 보장한다. (정책 §3.5) */
@Repository
@RequiredArgsConstructor
public class ValidationExecutionStore {
    private final JdbcTemplate jdbc;
    private final ExecutionRuntimeRepository runtimes;
    private final OutboxEventWriter events;
    private final Clock clock;

    /**
     * 기한 내 작업 한 건을 경쟁 실행기를 기다리지 않고 선점한다.
     *
     * @return 선점한 소유권; 대상이 없으면 빈 값
     */
    @Transactional
    public Optional<ValidationLease> claim() {
        var instant = now();
        var rows = jdbc.query("""
            SELECT * FROM judge_validation_jobs
            WHERE status IN ('QUEUED','RETRY_WAITING') AND next_run_at <= ? AND deadline_at > ? AND try_count < 3
            ORDER BY next_run_at, validation_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, (row, index) -> lease(row), instant, instant);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var previous = rows.getFirst();
        var token = UUID.randomUUID().toString();
        instant = now();
        if (!instant.isBefore(previous.deadlineAt())) {
            return Optional.empty();
        }
        jdbc.update("""
            UPDATE judge_validation_jobs SET status='RUNNING', try_count=try_count+1,
            lease_token=?, lease_until=?, started_at=?, updated_at=? WHERE validation_id=?
            """, token, instant.plusSeconds(120), instant, instant, previous.request().validationId());
        return Optional
            .of(
                new ValidationLease(
                    previous.request(),
                    token,
                    previous.attempt() + 1,
                    previous.correlationId(),
                    previous.deadlineAt()
                )
            );
    }

    /**
     * 확정 실행 환경을 조회한다. 외부 가용성 조회는 호출자가 트랜잭션 밖에서 수행한다.
     *
     * @param lease 소유권
     * @return 기존 환경 또는 최초 실행이면 null
     */
    @Transactional(readOnly = true)
    public JudgeRuntime runtime(ValidationLease lease) {
        var ids
            = jdbc
                .queryForList(
                    "SELECT runtime_id FROM judge_validation_jobs WHERE validation_id=?",
                    Long.class,
                    lease.request().validationId()
                );
        return ids.isEmpty() || ids.getFirst() == null ? null
            : runtimes.findById(ids.getFirst()).orElseThrow().toRuntime();
    }

    /**
     * 불변 테스트 순서와 실행 환경을 확정하고 새 시도의 결과 자리를 준비한다.
     *
     * @param lease 소유권
     * @param data 검증한 고정 원문
     * @param runtime 가용성을 확인한 환경
     * @return 현재 소유권이면 true
     * @throws IllegalArgumentException 고정 테스트 순서 또는 환경이 달라진 경우
     * @throws IllegalStateException 환경 무결성 오류 또는 잠금 대기 후 소유권이 만료되어 롤백하는 경우
     */
    @Transactional
    public boolean prepare(ValidationLease lease, ProblemValidationDataResponse data, JudgeRuntime runtime) {
        if (!owns(lease)) {
            return false;
        }
        data.validate(lease.request());
        var snapshot = ExecutionRuntime.snapshot(runtime, now());
        runtimes.insertIfAbsent(snapshot);
        var stored = runtimes.findBySnapshotHash(snapshot.getSnapshotHash()).orElseThrow();
        Assert.state(stored.matches(runtime), "실행 환경 스냅샷이 일치하지 않습니다.");
        Long fixed
            = jdbc
                .queryForObject(
                    "SELECT runtime_id FROM judge_validation_jobs WHERE validation_id=?",
                    Long.class,
                    lease.request().validationId()
                );
        Assert.isTrue(fixed == null || fixed.equals(stored.getId()), "고정 실행 환경을 변경할 수 없습니다.");
        var existing = jdbc.queryForList("""
            SELECT test_asset_id FROM judge_validation_test_results WHERE validation_id=? ORDER BY test_order
            """, Long.class, lease.request().validationId());
        var ids = data.testCases().stream().map(test -> Long.parseLong(test.testAssetId())).toList();
        Assert.isTrue(existing.isEmpty() || existing.equals(ids), "고정 테스트 순서가 일치하지 않습니다.");
        // 공유 환경 잠금을 기다린 사이 기한·임대가 만료되었으면 저장 전체를 롤백한다.
        Assert.state(owns(lease), "검증 실행 소유권이 만료되었습니다.");
        var instant = now();
        jdbc
            .update(
                "UPDATE judge_validation_jobs SET runtime_id=?, updated_at=? WHERE validation_id=?",
                stored.getId(),
                instant,
                lease.request().validationId()
            );
        if (existing.isEmpty()) {
            for (int index = 0; index < ids.size(); index++) {
                jdbc.update("""
                    INSERT INTO judge_validation_test_results
                    (validation_id,test_asset_id,test_order,token_status,created_at,updated_at)
                    VALUES (?,?,?,'NOT_CREATED',?,?)
                    """, lease.request().validationId(), ids.get(index), index + 1, instant, instant);
            }
        } else {
            jdbc.update("""
                UPDATE judge_validation_test_results SET judge0_token=NULL,token_status='NOT_CREATED',
                created_try_count=NULL,verdict=NULL,time_ms=NULL,memory_kb=NULL,updated_at=? WHERE validation_id=?
                """, instant, lease.request().validationId());
        }
        return true;
    }

    /**
     * 외부 호출 직전에 현재 소유권과 실행 기한을 확인한다.
     *
     * @param lease 소유권
     * @return 호출 허용 여부
     */
    @Transactional
    public boolean active(ValidationLease lease) {
        return owns(lease);
    }

    /**
     * 만료되지 않은 현재 소유권의 임대만 갱신한다.
     *
     * @param lease 소유권
     * @return 갱신 여부
     */
    @Transactional
    public boolean heartbeat(ValidationLease lease) {
        if (!owns(lease)) {
            return false;
        }
        var instant = now();
        jdbc
            .update(
                "UPDATE judge_validation_jobs SET lease_until=?,updated_at=? WHERE validation_id=?",
                instant.plusSeconds(120),
                instant,
                lease.request().validationId()
            );
        return true;
    }

    /**
     * 외부 생성 전에 불명 상태를 기록하여 응답 유실 시 중복 생성을 막는다.
     *
     * @param lease 소유권
     * @param order 테스트 순서
     * @return 생성 허용 여부
     */
    @Transactional
    public boolean beginCreate(ValidationLease lease, int order) {
        return owns(lease) && jdbc.update("""
            UPDATE judge_validation_test_results SET token_status='UNKNOWN',created_try_count=?,updated_at=?
            WHERE validation_id=? AND test_order=? AND token_status='NOT_CREATED'
            """, lease.attempt(), now(), lease.request().validationId(), order) == 1;
    }

    /**
     * 현 시도의 생성 응답만 기록한다.
     *
     * @param lease 소유권
     * @param order 테스트 순서
     * @param token Judge0 토큰
     * @return 반영 여부
     * @throws IllegalArgumentException 토큰이 비어 있거나 최대 길이를 넘는 경우
     */
    @Transactional
    public boolean saveToken(ValidationLease lease, int order, String token) {
        Assert.hasText(token, "Judge0 토큰이 필요합니다.");
        Assert.isTrue(token.length() <= 64, "Judge0 토큰이 너무 깁니다.");
        return owns(lease) && jdbc.update("""
            UPDATE judge_validation_test_results SET judge0_token=?,token_status='CREATED',updated_at=?
            WHERE validation_id=? AND test_order=? AND created_try_count=? AND token_status='UNKNOWN'
            """, token, now(), lease.request().validationId(), order, lease.attempt()) == 1;
    }

    /**
     * 원문 없이 현 시도의 테스트 판정과 자원 측정만 저장한다.
     *
     * @param lease 소유권
     * @param order 테스트 순서
     * @param result 완료 판정
     * @return 반영 여부
     * @throws IllegalArgumentException 완료 판정이 없는 경우
     * @throws ArithmeticException 측정 메모리가 저장 가능한 정수 범위를 넘는 경우
     */
    @Transactional
    public boolean saveResult(ValidationLease lease, int order, JudgeTestResult result) {
        Assert.notNull(result.verdict(), "완료 판정이 필요합니다.");
        Integer memory
            = result.memoryKiB() == null ? null : result.memoryKiB().setScale(0, RoundingMode.CEILING).intValueExact();
        return owns(lease) && jdbc
            .update(
                """
                    UPDATE judge_validation_test_results SET token_status='DONE',verdict=?,
                    time_ms=?,memory_kb=?,updated_at=?
                    WHERE validation_id=? AND test_order=? AND created_try_count=? AND token_status='CREATED'
                    """,
                result.verdict().name(),
                result.timeMs(),
                memory,
                now(),
                lease.request().validationId(),
                order,
                lease.attempt()
            ) == 1;
    }

    /**
     * 전체 테스트 완료를 확인하고 최종 시도·작업·결과 이벤트를 함께 종결한다.
     *
     * @param lease 소유권
     * @param runnerVersion 실제 사용한 생성기 식별자
     * @return 반영 여부
     * @throws IllegalArgumentException 생성기 정보가 없거나 현재 시도의 모든 코드 판정이 완료되지 않은 경우
     */
    @Transactional
    public boolean complete(ValidationLease lease, String runnerVersion) {
        if (!owns(lease)) {
            return false;
        }
        Assert.hasText(runnerVersion, "실제 생성기 정보가 필요합니다.");
        var results = jdbc.queryForList("""
            SELECT CASE WHEN created_try_count=? AND token_status='DONE' THEN verdict ELSE NULL END
            FROM judge_validation_test_results WHERE validation_id=? ORDER BY test_order
            """, String.class, lease.attempt(), lease.request().validationId());
        Assert
            .isTrue(
                !results.isEmpty()
                    && results.stream().allMatch(value -> value != null && !value.equals("SYSTEM_ERROR")),
                "전체 테스트 판정이 필요합니다."
            );
        if (!owns(lease)) {
            return false;
        }
        String status = results.stream().allMatch("AC"::equals) ? "PASSED" : "REJECTED";
        finish(
            lease,
            status,
            "SUCCEEDED",
            runnerVersion,
            status.equals("REJECTED") ? "검증 코드가 전체 테스트를 통과하지 못했습니다." : null
        );
        return true;
    }

    /**
     * 시스템 실패 시 현재 시도를 기록하고 정해진 상한 안에서만 재시도를 예약한다.
     * 생성 응답 유실은 이 경로로 재생성하지 않고 불명 상태로 남긴다.
     *
     * @param lease 소유권
     * @param runnerVersion 사용한 생성기 정보; 준비 전이면 null
     * @return 반영 여부
     */
    @Transactional
    public boolean fail(ValidationLease lease, String runnerVersion) {
        if (!owns(lease)) {
            return false;
        }
        Integer unresolved = jdbc.queryForObject("""
            SELECT COUNT(*) FROM judge_validation_test_results
            WHERE validation_id=? AND created_try_count=? AND token_status IN ('UNKNOWN','CREATED')
            """, Integer.class, lease.request().validationId(), lease.attempt());
        if (unresolved != null && unresolved > 0) {
            return false;
        }
        finish(
            lease,
            lease.attempt() < 3 ? "RETRY_WAITING" : "ERROR",
            "SYSTEM_ERROR",
            runnerVersion,
            "검증 실행 중 시스템 오류가 발생했습니다."
        );
        return true;
    }

    /**
     * 기한이 지난 작업 한 건을 실행기·서킷과 무관하게 종결한다.
     *
     * @return 처리 대상 존재 여부
     */
    @Transactional
    public boolean expireOne() {
        var rows = jdbc.query("""
            SELECT * FROM judge_validation_jobs WHERE status IN ('QUEUED','RUNNING','RETRY_WAITING')
            AND deadline_at<=? ORDER BY deadline_at LIMIT 1 FOR UPDATE SKIP LOCKED
            """, (row, index) -> lease(row), now());
        if (rows.isEmpty()) {
            return false;
        }
        var lease = rows.getFirst();
        String status
            = jdbc
                .queryForObject(
                    "SELECT status FROM judge_validation_jobs WHERE validation_id=?",
                    String.class,
                    lease.request().validationId()
                );
        // 대기 작업의 기존 시도 이력은 보존하며 실행하지 않은 시도를 만들지 않는다.
        if (status.equals("RUNNING")) {
            recordRun(lease, "SYSTEM_ERROR", null, "검증 처리 기한이 초과되었습니다.", now());
        }
        terminal(lease, "ERROR", null, "검증 처리 기한이 초과되었습니다.", now());
        return true;
    }

    /**
     * 작업 행을 잠근 뒤 현재 시각으로 소유권을 검증한다.
     *
     * @param lease 소유권
     * @return 아직 유효한 현재 실행 여부
     */
    private boolean owns(ValidationLease lease) {
        return Boolean.TRUE
            .equals(
                jdbc
                    .query(
                        "SELECT status,lease_token,try_count,lease_until,deadline_at "
                            + "FROM judge_validation_jobs WHERE validation_id=? FOR UPDATE",
                        row -> {
                            if (!row.next()) {
                                return false;
                            }
                            var instant = now();
                            return "RUNNING".equals(row.getString("status"))
                                && lease.token().equals(row.getString("lease_token"))
                                && row.getInt("try_count") == lease.attempt()
                                && instant.isBefore(row.getTimestamp("lease_until").toLocalDateTime())
                                && instant.isBefore(row.getTimestamp("deadline_at").toLocalDateTime());
                        },
                        lease.request().validationId()
                    )
            );
    }

    /**
     * 종료 이력과 재시도 또는 종결 상태를 반영한다.
     *
     * @param lease 소유권
     * @param status 다음 상태
     * @param result 시도 종료 사유
     * @param runner 생성기 정보
     * @param message 정제한 설명
     */
    private void finish(ValidationLease lease, String status, String result, String runner, String message) {
        var instant = now();
        recordRun(lease, result, runner, message, instant);
        if (status.equals("RETRY_WAITING")) {
            jdbc
                .update(
                    """
                        UPDATE judge_validation_jobs SET status='RETRY_WAITING',lease_token=NULL,lease_until=NULL,
                        next_run_at=?,last_error=?,updated_at=? WHERE validation_id=?
                        """,
                    instant.plusSeconds(lease.attempt() == 1 ? 10 : 30),
                    message,
                    instant,
                    lease.request().validationId()
                );
        } else {
            terminal(lease, status, runner, message, instant);
        }
    }

    /**
     * 종결한 실제 시도 한 건의 불변 요약을 기록한다.
     *
     * @param lease 소유권
     * @param result 종료 사유
     * @param runner 생성기 정보
     * @param message 정제한 설명
     * @param instant 종결 시각
     */
    private void recordRun(ValidationLease lease, String result, String runner, String message, LocalDateTime instant) {
        jdbc
            .update(
                """
                    INSERT INTO judge_validation_runs
                    (validation_id,try_count,lease_token,runner_version,result,
                    max_time_ms,error_message,started_at,finished_at,created_at)
                    SELECT validation_id,try_count,lease_token,?,?,
                    (SELECT MAX(time_ms) FROM judge_validation_test_results
                    WHERE validation_id=? AND created_try_count=?),
                    ?,started_at,?,? FROM judge_validation_jobs WHERE validation_id=?
                    """,
                runner,
                result,
                lease.request().validationId(),
                lease.attempt(),
                message,
                instant,
                instant,
                lease.request().validationId()
            );
    }

    /**
     * 종결 상태와 같은 트랜잭션에서 원 요청 추적 정보를 가진 Outbox를 기록한다.
     *
     * @param lease 작업 식별
     * @param status 종결 상태
     * @param runner 생성기 정보
     * @param message 정제한 설명
     * @param instant 종결 시각
     */
    private void terminal(ValidationLease lease, String status, String runner, String message, LocalDateTime instant) {
        jdbc.update("""
            UPDATE judge_validation_jobs SET status=?,finished_at=?,last_error=?,lease_token=NULL,lease_until=NULL,
            updated_at=? WHERE validation_id=?
            """, status, instant, message, instant, lease.request().validationId());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("validationId", Long.toString(lease.request().validationId()));
        payload.put("problemRevisionId", Long.toString(lease.request().problemRevisionId()));
        payload.put("language", lease.request().language());
        payload.put("requestHash", lease.request().requestHash());
        payload.put("status", status);
        String key = status.equals("PASSED") ? jdbc.queryForObject("""
            SELECT r.snapshot_hash FROM execution_runtimes r JOIN judge_validation_jobs j ON j.runtime_id=r.id
            WHERE j.validation_id=?
            """, String.class, lease.request().validationId()) : null;
        payload.put("runtimeKey", key);
        payload.put("runnerVersion", status.equals("PASSED") ? runner : null);
        payload.put("finishedAt", instant.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime().toString());
        events
            .append(
                "ProblemValidation",
                "ProblemValidationCompleted",
                "v1",
                Long.toString(lease.request().validationId()),
                lease.correlationId(),
                payload
            );
    }

    /**
     * DB 행을 원 요청과 소유권 스냅샷으로 변환한다.
     *
     * @param row 조회 행
     * @return 소유권 스냅샷
     * @throws SQLException 행을 읽을 수 없는 경우
     */
    private ValidationLease lease(ResultSet row) throws SQLException {
        return new ValidationLease(
            new ProblemValidationRequest(
                row.getLong("validation_id"),
                row.getLong("problem_revision_id"),
                row.getString("language"),
                row.getString("request_hash")
            ),
            row.getString("lease_token"),
            row.getInt("try_count"),
            row.getString("correlation_id"),
            row.getTimestamp("deadline_at").toLocalDateTime()
        );
    }

    /**
     * 공통 서비스 시간대의 마이크로초 시각을 반환한다.
     *
     * @return 저장 기준 시각
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
    }
}
