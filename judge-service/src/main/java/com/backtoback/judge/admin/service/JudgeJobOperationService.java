package com.backtoback.judge.admin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.admin.dto.JudgeJobListRequest;
import com.backtoback.judge.admin.service.dto.JudgeJobDetails;
import com.backtoback.judge.admin.service.dto.JudgeJobState;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;

import lombok.RequiredArgsConstructor;

/** 관리자 작업 조회와 멱등 복구의 상태·감사를 단일 DB 트랜잭션으로 처리한다. */
@Service
@RequiredArgsConstructor
public class JudgeJobOperationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 허용된 필터와 안정적인 시각·ID 정렬로 작업 목록을 조회한다. (API 명세서 §3.2.1)
     *
     * @param request 검증한 목록 조건
     * @return 원문을 제외한 작업 상태 페이지
     */
    @Transactional(readOnly = true)
    public Page<JudgeJobState> list(JudgeJobListRequest request) {
        var args = new ArrayList<Object>();
        var where = new StringBuilder(" WHERE 1=1");
        if (request.status() != null) {
            where.append(" AND j.status=?");
            args.add(request.status().name());
        }
        if (request.context() != null) {
            where.append(" AND s.context=?");
            args.add(request.context().name());
        }
        if (request.contextId() != null) {
            where.append(" AND s.context_id=?");
            args.add(Long.parseLong(request.contextId()));
        }
        if (request.failureCode() != null) {
            where.append(" AND j.last_error=?");
            args.add(request.failureCode());
        }
        String join = " FROM judge_jobs j JOIN submissions s ON s.id=j.submission_id";
        long count = jdbc.queryForObject("SELECT COUNT(*)" + join + where, Long.class, args.toArray());
        String order = request.sort().startsWith("createdAt") ? "j.created_at" : "j.updated_at";
        String direction = request.sort().endsWith(",asc") ? " ASC" : " DESC";
        args.add(request.size());
        args.add((long)request.page() * request.size());
        var rows
            = jdbc
                .query(
                    "SELECT j.*,s.member_id,s.context,s.context_id,s.problem_revision_id" + join + where + " ORDER BY "
                        + order + direction + ",j.id" + direction + " LIMIT ? OFFSET ?",
                    this::readJob,
                    args.toArray()
                );
        return new PageImpl<>(rows, PageRequest.of(request.page(), request.size()), count);
    }

    /**
     * 같은 읽기 트랜잭션에서 작업·실행 시도·토큰 상태를 조회한다. (API 명세서 §3.2.2)
     *
     * @param jobId 조회 작업 ID
     * @return 원문을 제외한 현재 상세
     * @throws JudgeException 작업이 없으면 {@code SUBMISSION_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public JudgeJobDetails detail(long jobId) {
        var rows = jdbc.query("""
            SELECT j.*,s.member_id,s.context,s.context_id,s.problem_revision_id
            FROM judge_jobs j JOIN submissions s ON s.id=j.submission_id WHERE j.id=?
            """, this::readJob, jobId);
        if (rows.isEmpty()) {
            throw new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND);
        }
        var runs
            = jdbc
                .query(
                    """
                        SELECT try_count,result,error_message,started_at,finished_at FROM judge_job_runs
                        WHERE judge_job_id=? ORDER BY try_count
                        """,
                    (row, index) -> new JudgeJobDetails.Run(
                        row.getInt("try_count"),
                        row.getString("result"),
                        safeError(row.getString("error_message")),
                        row.getObject("started_at", LocalDateTime.class),
                        row.getObject("finished_at", LocalDateTime.class)
                    ),
                    jobId
                );
        var tests
            = jdbc
                .query(
                    """
                        SELECT test_order,token_status,judge0_token,verdict FROM judge_test_results
                        WHERE judge_job_id=? ORDER BY test_order
                        """,
                    (row, index) -> new JudgeJobDetails.Test(
                        row.getInt("test_order"),
                        row.getString("token_status"),
                        row.getString("judge0_token"),
                        row.getString("verdict")
                    ),
                    jobId
                );
        return new JudgeJobDetails(rows.getFirst(), runs, tests);
    }

    /**
     * 작업·요청·제출 순서로 잠근 뒤 복구와 감사·최초 응답을 함께 저장한다. (API 명세서 §3.2.5)
     *
     * @param jobId 복구할 작업 ID
     * @param actorId Gateway가 인증한 관리자 ID
     * @param key 검증한 멱등키
     * @param reason 검증한 복구 사유
     * @param clientIp 요청의 네트워크 주소
     * @return 최초 성공 응답의 상태
     * @throws JudgeException 미존재·종결·기한 초과·시도 상한 또는 멱등키 충돌인 경우
     */
    @Transactional(timeout = 5)
    public String recover(long jobId, long actorId, String key, String reason, String clientIp) {
        String normalized = reason.strip();
        String hash = hash(normalized);
        var instant = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        var jobs = jdbc.queryForList("SELECT * FROM judge_jobs WHERE id=? FOR UPDATE", jobId);
        if (jobs.isEmpty()) {
            throw new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND);
        }
        var job = jobs.getFirst();
        jdbc.update("""
            INSERT INTO judge_recovery_requests(actor_id,judge_job_id,idempotency_key,request_hash,reason,
                response_status,created_at) VALUES (?,?,?,?,?,'PENDING',?)
            ON DUPLICATE KEY UPDATE id=id
            """, actorId, jobId, key, hash, normalized, instant);
        var request = jdbc.queryForMap("""
            SELECT id,request_hash,response_status FROM judge_recovery_requests
            WHERE actor_id=? AND judge_job_id=? AND idempotency_key=? FOR UPDATE
            """, actorId, jobId, key);
        if (!hash.equals(request.get("request_hash"))) {
            throw new JudgeException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if ("RETRY_WAITING".equals(request.get("response_status"))) {
            return "RETRY_WAITING";
        }
        var submission = jdbc.queryForMap("SELECT * FROM submissions WHERE id=? FOR UPDATE", job.get("submission_id"));
        instant = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        String status = (String)job.get("status");
        int attempt = ((Number)job.get("try_count")).intValue();
        boolean running = "RUNNING".equals(status);
        if (!(running || "PENDING".equals(status) || "RETRY_WAITING".equals(status))
            || !instant.isBefore((LocalDateTime)job.get("deadline_at"))
            || !job.get("judge_attempt").equals(submission.get("judge_attempt")) || attempt > 3
            || (running && attempt >= 3)) {
            throw new JudgeException(JudgeErrorCode.SUBMISSION_REJUDGE_NOT_ALLOWED);
        }
        if (running) {
            jdbc.update("""
                INSERT INTO judge_job_runs(judge_job_id,try_count,lease_token,result,error_message,
                    started_at,finished_at,created_at) VALUES (?,?,?,'SYSTEM_ERROR','MANUAL_RECOVERY',?,?,?)
                """, jobId, attempt, job.get("lease_token"), job.get("started_at"), instant, instant);
        }
        jdbc.update("""
            UPDATE judge_jobs SET status='RETRY_WAITING',try_count=?,
                last_error=CASE WHEN ? THEN 'LEASE_EXPIRED' ELSE last_error END,
                unknown_retry_count=CASE WHEN ? THEN 0 ELSE unknown_retry_count END,
                lease_token=CASE WHEN ? THEN NULL ELSE lease_token END,lease_until=NULL,
                next_run_at=GREATEST(?,COALESCE((SELECT MAX(updated_at)+INTERVAL 30 SECOND
                    FROM judge_test_results WHERE judge_job_id=? AND token_status='UNKNOWN'),?)),updated_at=? WHERE id=?
            """, running ? attempt + 1 : attempt, running, running, running, instant, jobId, instant, instant, jobId);
        jdbc
            .update(
                "UPDATE submissions SET status='QUEUED',updated_at=? WHERE id=?",
                instant,
                job.get("submission_id")
            );
        jdbc
            .update(
                """
                    INSERT INTO audit_logs(actor_id,action,target_type,target_id,before_state,after_state,
                        reason,client_ip,created_at)
                    SELECT ?,'JUDGE_JOB_RECOVERY','JUDGE_JOB',?,
                        JSON_OBJECT('status',?,'tryCount',?,'nextRunAt',CAST(? AS DATETIME(6)),
                    'leaseUntil',CAST(? AS DATETIME(6))),
                        JSON_OBJECT('status',status,'tryCount',try_count,'nextRunAt',next_run_at,
                            'leaseUntil',lease_until,'requestId',?),?,?,?
                    FROM judge_jobs WHERE id=?
                    """,
                actorId,
                Long.toString(jobId),
                status,
                attempt,
                job.get("next_run_at"),
                job.get("lease_until"),
                request.get("id"),
                normalized,
                clientIp,
                instant,
                jobId
            );
        jdbc.update("UPDATE judge_recovery_requests SET response_status='RETRY_WAITING' WHERE id=?", request.get("id"));
        return "RETRY_WAITING";
    }

    /**
     * JDBC 결과를 서비스 상태로 옮긴다.
     *
     * @param row 작업과 제출 조인 결과
     * @param index 행 번호
     * @return 작업의 스칼라 상태
     * @throws SQLException JDBC 값을 읽지 못한 경우
     */
    private JudgeJobState readJob(ResultSet row, int index) throws SQLException {
        return new JudgeJobState(
            row.getLong("id"),
            row.getLong("submission_id"),
            row.getInt("judge_attempt"),
            row.getString("status"),
            row.getInt("try_count"),
            safeError(row.getString("last_error")),
            row.getLong("member_id"),
            row.getString("context"),
            row.getObject("context_id", Long.class),
            row.getLong("problem_revision_id"),
            row.getObject("deadline_at", LocalDateTime.class),
            row.getObject("created_at", LocalDateTime.class),
            row.getObject("updated_at", LocalDateTime.class),
            row.getObject("lease_until", LocalDateTime.class),
            row.getObject("next_run_at", LocalDateTime.class)
        );
    }

    /**
     * 예외 원문이나 응답 본문을 작업 조회에 노출하지 않는다.
     *
     * @param error 저장된 오류 코드 또는 원문
     * @return 안전한 코드 또는 {@code null}
     */
    private String safeError(String error) {
        return error == null || error.matches("[A-Z0-9_]{1,100}") ? error : "SYSTEM_ERROR";
    }

    /**
     * 정규화한 사유의 멱등 비교 해시를 계산한다.
     *
     * @param reason 정규화한 사유
     * @return SHA-256 십육진수
     */
    private String hash(String reason) {
        try {
            return HexFormat
                .of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(reason.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
