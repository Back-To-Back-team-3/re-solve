package com.backtoback.contest.exam.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;

import lombok.RequiredArgsConstructor;

/**
 * 원래 접수와 회차를 검증하고 시험 점수와 결과 버전을 원자적으로 반영한다.
 * <p>기준 문서</p>
 * <ul><li>도메인 및 데이터베이스 v1.0 / §6.4.2 채점·순위·확정: 회차와 마지막 유효 제출</li></ul>
 */
@Service
@RequiredArgsConstructor
public class ExamJudgementService {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 소비 기록과 같은 트랜잭션에서 결과를 적용하거나 상충 결과를 거절한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Result result) {
        var exam
            = exams.findForUpdate(result.examId()).orElseThrow(() -> new IllegalArgumentException("Exam not found"));
        var participant
            = participants
                .findForUpdate(result.examId(), result.memberId())
                .stream()
                .filter(p -> p.getEnteredAt() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Participant mismatch"));
        var rows
            = jdbc
                .query(
                    "SELECT s.problem_id,p.problem_revision_id,s.received_at,s.submission_id,"
                        + "s.judge_attempt,s.status,s.verdict,s.passed_count,s.total_count,p.score "
                        + "FROM exam_submissions s "
                        + "JOIN exam_problems p ON p.exam_id=s.exam_id AND p.problem_id=s.problem_id "
                        + "WHERE s.id=? AND s.exam_id=? AND s.exam_participant_id=? FOR UPDATE",
                    (rs, row) -> new Stored(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getObject(3, LocalDateTime.class),
                        rs.getObject(4, Long.class),
                        rs.getObject(5, Integer.class),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getObject(8, Integer.class),
                        rs.getObject(9, Integer.class),
                        rs.getBigDecimal(10)
                    ),
                    result.receiptId(),
                    result.examId(),
                    participant.getId()
                );
        if (rows.size() != 1) {
            throw new IllegalArgumentException("Receipt mismatch");
        }
        var stored = rows.getFirst();
        if (stored.problemId() != result.problemId() || stored.revisionId() != result.revisionId()
            || !stored
                .receivedAt()
                .equals(result.submittedAt().atZoneSameInstant(ClockConfiguration.SERVICE_ZONE).toLocalDateTime())
            || stored.submissionId() != null && stored.submissionId() != result.submissionId()) {
            throw new IllegalArgumentException("Receipt connection mismatch");
        }
        int previous = stored.attempt() == null ? 0 : stored.attempt();
        if (result.attempt() < previous) {
            return;
        }
        if (result.attempt() == previous) {
            String terminalStatus
                = "REQUESTED".equals(stored.status()) ? (stored.verdict() == null ? "FAILED" : "JUDGED")
                    : stored.status();
            if (!Objects.equals(stored.verdict(), result.verdict()) || !Objects.equals(stored.passed(), result.passed())
                || !Objects.equals(stored.total(), result.total()) || !terminalStatus.equals(result.status())) {
                throw new IllegalArgumentException("Conflicting result for judge attempt");
            }
            return;
        }
        if (exam.getStatus() == ExamStatus.FINALIZED) {
            throw new IllegalStateException("New result after finalization");
        }
        if (previous == 0 ? result.attempt() != 1 : !allowedRetry(result.receiptId(), previous, result.attempt())) {
            throw new IllegalArgumentException("Unexpected judge attempt");
        }
        var score
            = result.status().equals("JUDGED") && !"CE".equals(result.verdict()) ? stored
                .score()
                .multiply(BigDecimal.valueOf(result.passed()))
                .divide(BigDecimal.valueOf(result.total()), 2, RoundingMode.DOWN) : BigDecimal.ZERO;
        var now = now();
        jdbc
            .update(
                "UPDATE exam_submissions SET submission_id=?,judge_attempt=?,status=?,verdict=?,passed_count=?,"
                    + "total_count=?,score=?,is_zero_confirmed=false,updated_at=? WHERE id=?",
                result.submissionId(),
                result.attempt(),
                result.status(),
                result.verdict(),
                result.passed(),
                result.total(),
                result.status().equals("JUDGED") ? score : null,
                now,
                result.receiptId()
            );
        refreshProblem(result.examId(), participant.getId(), result.problemId(), now);
    }

    /** 관리자 재처리 작업이 확인한 목표 회차만 허용한다. */
    private boolean allowedRetry(long receipt, int previous, int target) {
        Integer count
            = jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM contest_admin_operations WHERE context='EXAM' "
                        + "AND operation_type='EXAM_RETRY' AND receipt_id=? AND expected_judge_attempt=? "
                        + "AND ((state='PENDING' AND target_judge_attempt IS NULL) "
                        + "OR (state IN ('PENDING','SUCCEEDED') AND target_judge_attempt=?))",
                    Integer.class,
                    receipt,
                    previous,
                    target
                );
        return target == previous + 1 && count != null && count > 0;
    }

    /** 마지막 접수만 점수에 반영하고 미종결·실패는 이전 점수로 대체하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshProblem(long examId, long participantId, long problemId, LocalDateTime now) {
        var latest
            = jdbc
                .query(
                    "SELECT id,status,score,received_at FROM exam_submissions "
                        + "WHERE exam_participant_id=? AND problem_id=? "
                        + "ORDER BY received_at DESC,draft_seq DESC,id DESC LIMIT 1",
                    (rs, row) -> new Latest(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getBigDecimal(3),
                        rs.getObject(4, LocalDateTime.class)
                    ),
                    participantId,
                    problemId
                )
                .stream()
                .findFirst()
                .orElseThrow();
        BigDecimal score
            = "JUDGED".equals(latest.status()) && latest.score() != null ? latest.score() : BigDecimal.ZERO;
        jdbc
            .update(
                "INSERT INTO exam_problem_results (exam_participant_id,problem_id,last_valid_exam_submission_id,"
                    + "score,version,created_at,updated_at) VALUES (?,?,?,?,0,?,?) ON DUPLICATE KEY UPDATE "
                    + "last_valid_exam_submission_id=VALUES(last_valid_exam_submission_id),score=VALUES(score),"
                    + "version=version+1,updated_at=VALUES(updated_at)",
                participantId,
                problemId,
                latest.id(),
                score,
                now,
                now
            );
        jdbc
            .update(
                "UPDATE exam_participants SET total_score=(SELECT COALESCE(SUM(score),0) FROM exam_problem_results "
                    + "WHERE exam_participant_id=?),last_valid_submitted_at=(SELECT MAX(received_at) "
                    + "FROM exam_submissions "
                    + "WHERE exam_participant_id=?),is_ranked=true,version=version+1,updated_at=? WHERE id=?",
                participantId,
                participantId,
                now,
                participantId
            );
        jdbc
            .update(
                "UPDATE exams SET result_revision=result_revision+1,version=version+1,updated_at=? WHERE id=?",
                now,
                examId
            );
    }

    /** 서버 시각을 DB 정밀도로 반환한다. */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
    }

    /** 접수와 대조할 종결 결과다. */
    public record Result(
        long examId,
        long receiptId,
        long submissionId,
        long memberId,
        long problemId,
        long revisionId,
        int attempt,
        String status,
        String verdict,
        Integer passed,
        Integer total,
        OffsetDateTime submittedAt
    ) {
    }
    /** 현재 접수와 고정 문제의 DB 값이다. */
    private record Stored(
        long problemId,
        long revisionId,
        LocalDateTime receivedAt,
        Long submissionId,
        Integer attempt,
        String status,
        String verdict,
        Integer passed,
        Integer total,
        BigDecimal score
    ) {
    }
    /** 점수 갱신에 필요한 마지막 접수다. */
    private record Latest(long id, String status, BigDecimal score, LocalDateTime receivedAt) {
    }
}
