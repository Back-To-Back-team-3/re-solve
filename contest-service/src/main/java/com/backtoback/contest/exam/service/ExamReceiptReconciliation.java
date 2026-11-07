package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.ExamReceiptClient.Receipt;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.service.ExamJudgementService.Result;

import lombok.RequiredArgsConstructor;

/**
 * 원격 조회 후 원래 접수를 다시 잠가 결과 또는 기존 Outbox를 복구한다.
 * <p>기준 문서</p>
 * <ul><li>스케줄러 v1.0 / §4.6 접수·채점 대사: 연결 검증과 기존 요청 복구</li></ul>
 */
@Service
@RequiredArgsConstructor
public class ExamReceiptReconciliation {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamJudgementService results;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 조회 사이 변경된 상태를 잠금 아래 재검증한다. */
    @Transactional
    public void reconcile(long receiptId, Receipt remote) {
        var rows
            = jdbc
                .query(
                    "SELECT s.exam_id,p.member_id FROM exam_submissions s "
                        + "JOIN exam_participants p ON p.id=s.exam_participant_id WHERE s.id=?",
                    (rs, row) -> new Owner(rs.getLong(1), rs.getLong(2)),
                    receiptId
                );
        if (rows.size() != 1) {
            throw new IllegalStateException("Receipt not found");
        }
        var owner = rows.getFirst();
        var exam = exams.findForUpdate(owner.exam()).orElseThrow();
        var participant
            = participants
                .findForUpdate(owner.exam(), owner.member())
                .stream()
                .filter(p -> p.getEnteredAt() != null)
                .findFirst()
                .orElseThrow();
        var current
            = jdbc
                .query(
                    "SELECT s.status,s.problem_id,p.problem_revision_id,s.received_at "
                        + "FROM exam_submissions s JOIN exam_problems p ON p.exam_id=s.exam_id "
                        + "AND p.problem_id=s.problem_id " + "WHERE s.id=? AND s.exam_participant_id=? FOR UPDATE",
                    (rs, row) -> new Current(
                        rs.getString(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getObject(4, LocalDateTime.class)
                    ),
                    receiptId,
                    participant.getId()
                )
                .stream()
                .findFirst()
                .orElseThrow();
        if (exam.getStatus() == ExamStatus.FINALIZED || "JUDGED".equals(current.status())) {
            return;
        }
        if (!Boolean.TRUE.equals(remote.found())) {
            int changed
                = jdbc
                    .update(
                        "UPDATE outbox_events SET status='PENDING',retry_count=0,published_at=NULL,"
                            + "updated_at=? WHERE event_type='ExamSubmissionRequested' AND aggregate_id=? "
                            + "AND status IN ('PUBLISHED','FAILED')",
                        LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE),
                        Long.toString(receiptId)
                    );
            if (changed == 0) {
                Integer count
                    = jdbc
                        .queryForObject(
                            "SELECT COUNT(*) FROM outbox_events WHERE "
                                + "event_type='ExamSubmissionRequested' AND aggregate_id=? AND status='PENDING'",
                            Integer.class,
                            Long.toString(receiptId)
                        );
                if (count == null || count != 1) {
                    throw new IllegalStateException("Original Outbox is unavailable");
                }
            } else if (changed != 1) {
                throw new IllegalStateException("Multiple original Outbox events");
            }
            return;
        }
        if (!"EXAM".equals(remote.context()) || !Long.toString(owner.exam()).equals(remote.contextId())
            || !Long.toString(receiptId).equals(remote.receiptId())
            || !Long.toString(owner.member()).equals(remote.userId())
            || !Long.toString(current.problem()).equals(remote.problemId())
            || !Long.toString(current.revision()).equals(remote.problemRevisionId()) || remote.submittedAt() == null
            || !current
                .receivedAt()
                .equals(remote.submittedAt().atZoneSameInstant(ClockConfiguration.SERVICE_ZONE).toLocalDateTime())) {
            throw new IllegalArgumentException("Remote receipt connection mismatch");
        }
        if (remote.judgeAttempt() == null || remote.judgeAttempt() < 1 || remote.submissionId() == null) {
            throw new IllegalArgumentException("Invalid remote receipt");
        }
        if (Set.of("QUEUED", "JUDGING", "RUNNING", "RETRY_WAITING").contains(remote.status())) {
            return;
        }
        boolean failed = "FAILED".equals(remote.status());
        if (!failed && (!"COMPLETED".equals(remote.status()) || remote.verdict() == null
            || !Set.of("AC", "WA", "TLE", "MLE", "RE", "CE").contains(remote.verdict()) || remote.totalCount() == null
            || remote.totalCount() <= 0 || remote.passedCount() == null || remote.passedCount() < 0
            || remote.passedCount() > remote.totalCount())) {
            throw new IllegalArgumentException("Invalid remote result");
        }
        results
            .apply(
                new Result(
                    owner.exam(),
                    receiptId,
                    Long.parseLong(remote.submissionId()),
                    owner.member(),
                    current.problem(),
                    current.revision(),
                    remote.judgeAttempt(),
                    failed ? "FAILED" : "JUDGED",
                    failed ? null : remote.verdict(),
                    failed ? null : remote.passedCount(),
                    failed ? null : remote.totalCount(),
                    remote.submittedAt()
                )
            );
    }

    /** 접수 소유 시험과 회원이다. */
    private record Owner(long exam, long member) {
    }
    /** 잠금 아래 확인한 접수 연결이다. */
    private record Current(String status, long problem, long revision, LocalDateTime receivedAt) {
    }
}
