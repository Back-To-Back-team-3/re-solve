package com.backtoback.contest.exam.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * 종결 조건을 확인한 시험을 DB 기준으로 재집계해 불변 결과를 저장한다.
 * <p>기준 문서</p>
 * <ul><li>도메인 및 데이터베이스 v1.0 / §6.4.2 채점·순위·확정: 종결·순위·스냅샷</li>
 * <li>이벤트 계약서 v1.0 / §4.4 contest의 C11: 확정 이벤트</li></ul>
 */
@Service
@RequiredArgsConstructor
public class ExamFinalizationService {
    private final ExamRepository exams;
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 부모 잠금 아래 최초 확정 조건·결과·스냅샷·이벤트를 한 번에 처리한다. */
    @Transactional
    public boolean finalizeExam(long examId) {
        var exam = exams.findForUpdate(examId).orElse(null);
        if (exam == null || exam.getStatus() != ExamStatus.CLOSED || blocked(examId)) {
            return false;
        }
        saveSnapshot(examId, exam.getResultRevision(), false);
        return true;
    }

    /**
     * 관리자 작업 트랜잭션에서 확정된 시험의 기존 유효 제출만 다시 집계한다.
     * 이전 스냅샷과 최초 확정 시각을 보존하고 새 결과와 이벤트를 저장한다.
     *
     * @param examId 부모 시험 ID
     * @param expectedRevision 관리자가 확인한 이전 결과 버전
     * @return 새 결과 버전의 불변 스냅샷
     * @throws IllegalStateException 상태·버전이 일치하지 않거나 미종결 작업이 남은 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot refinalize(long examId, long expectedRevision) {
        var exam = exams.findForUpdate(examId).orElseThrow();
        if (exam.getStatus() != ExamStatus.FINALIZED || exam.getResultRevision() != expectedRevision
            || blocked(examId)) {
            throw new IllegalStateException("Exam is not ready for refinalization");
        }
        return saveSnapshot(examId, expectedRevision, true);
    }

    /** 최초 확정과 재확정이 동일한 DB 집계 및 스냅샷 경로를 사용한다. */
    private Snapshot saveSnapshot(long examId, long previousRevision, boolean refinalized) {
        var now
            = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
        long revision = Math.incrementExact(previousRevision);
        var problems
            = jdbc
                .query(
                    "SELECT problem_id,problem_revision_id,score FROM exam_problems "
                        + "WHERE exam_id=? ORDER BY display_order",
                    (rs, row) -> new Problem(
                        Long.toString(rs.getLong(1)),
                        Long.toString(rs.getLong(2)),
                        rs.getBigDecimal(3).toPlainString()
                    ),
                    examId
                );
        var participants
            = jdbc
                .query(
                    "SELECT id,member_id,status FROM exam_participants WHERE exam_id=? "
                        + "AND status<>'CANCELED' ORDER BY id",
                    (rs, row) -> new Participant(rs.getLong(1), rs.getLong(2), rs.getString(3)),
                    examId
                );
        var calculated = new ArrayList<Calculated>();
        for (var participant : participants) {
            calculated.add(calculate(participant, problems, now));
        }
        calculated
            .sort(
                Comparator
                    .comparing(Calculated::ranked)
                    .reversed()
                    .thenComparing(Calculated::score, Comparator.reverseOrder())
                    .thenComparing(Calculated::lastSubmittedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingLong(value -> value.participant().memberId())
            );
        var items = new ArrayList<Item>();
        Calculated previous = null;
        int position = 0;
        int rank = 0;
        for (var row : calculated) {
            Integer finalRank = null;
            if (row.ranked()) {
                position++;
                if (previous == null || previous.score().compareTo(row.score()) != 0
                    || !previous.lastSubmittedAt().equals(row.lastSubmittedAt())) {
                    rank = position;
                }
                finalRank = rank;
                previous = row;
            }
            jdbc
                .update(
                    "UPDATE exam_participants SET total_score=?,last_valid_submitted_at=?,is_ranked=?,final_rank=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                    row.score(),
                    row.lastSubmittedAt(),
                    row.ranked(),
                    finalRank,
                    now,
                    row.participant().id()
                );
            items
                .add(
                    new Item(
                        Long.toString(row.participant().memberId()),
                        Long.toString(row.participant().id()),
                        row.ranked(),
                        finalRank,
                        row.score().toPlainString(),
                        null,
                        null,
                        row.problems()
                    )
                );
        }
        var payload
            = new Snapshot(
                "EXAM",
                Long.toString(examId),
                "FINALIZED",
                Long.toString(revision),
                format(now),
                problems,
                items
            );
        jdbc
            .update(
                "INSERT INTO result_snapshots (context,context_id,result_revision,payload,created_at) "
                    + "VALUES ('EXAM',?,?,?,?)",
                examId,
                revision,
                mapper.writeValueAsString(payload),
                now
            );
        jdbc
            .update(
                "UPDATE exams SET status='FINALIZED',result_revision=?,finalized_at=COALESCE(finalized_at,?),"
                    + "version=version+1," + "updated_at=? WHERE id=?",
                revision,
                now,
                now,
                examId
            );
        outbox
            .append(
                "EXAM",
                "ExamFinalized",
                "v1",
                Long.toString(examId),
                "contest-finalize-" + UUID.randomUUID(),
                new Finalized(
                    "EXAM",
                    Long.toString(examId),
                    Long.toString(examId),
                    revision,
                    format(now),
                    refinalized,
                    participants.stream().map(p -> Long.toString(p.memberId())).toList()
                )
            );
        return payload;
    }

    /** 진행·미확인 관리자 작업과 미종결 접수 중 하나라도 있으면 보류한다. */
    private boolean blocked(long examId) {
        return count(
            "SELECT COUNT(*) FROM exam_participants WHERE exam_id=? AND status<>'CANCELED' "
                + "AND (status NOT IN ('FINISHED','ABSENT') OR (entered_at IS NOT NULL AND auto_submitted_at IS NULL))",
            examId
        ) > 0
            || count(
                "SELECT COUNT(*) FROM exam_submissions WHERE exam_id=? AND "
                    + "(status NOT IN ('JUDGED','FAILED') OR (status='FAILED' AND is_zero_confirmed=false))",
                examId
            ) > 0
            || count(
                "SELECT COUNT(*) FROM contest_admin_operations WHERE context='EXAM' AND context_id=? "
                    + "AND state='PENDING'",
                examId
            ) > 0;
    }

    private long count(String sql, long examId) {
        return jdbc.queryForObject(sql, Long.class, examId);
    }

    private Calculated calculate(Participant participant, List<Problem> problems, LocalDateTime now) {
        var results = new ArrayList<ProblemResult>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        LocalDateTime last = null;
        long submissions = 0;
        for (var problem : problems) {
            var entries
                = jdbc
                    .query(
                        "SELECT id,status,verdict,score,passed_count,total_count,received_at "
                            + "FROM exam_submissions WHERE exam_participant_id=? AND problem_id=? "
                            + "ORDER BY received_at,draft_seq,id",
                        (rs, row) -> new Submission(
                            rs.getLong(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getBigDecimal(4),
                            rs.getObject(5, Integer.class),
                            rs.getObject(6, Integer.class),
                            rs.getObject(7, LocalDateTime.class)
                        ),
                        participant.id(),
                        Long.parseLong(problem.problemId())
                    );
            submissions += entries.size();
            var latest = entries.isEmpty() ? null : entries.getLast();
            var first = entries.isEmpty() ? null : entries.getFirst().receivedAt();
            var firstAccepted
                = entries
                    .stream()
                    .filter(entry -> "AC".equals(entry.verdict()))
                    .map(Submission::receivedAt)
                    .findFirst()
                    .orElse(null);
            if (latest != null && (last == null || latest.receivedAt().isAfter(last))) {
                last = latest.receivedAt();
            }
            BigDecimal score = latest != null && "JUDGED".equals(latest.status()) ? latest.score() : BigDecimal.ZERO;
            total = total.add(score);
            if (latest != null) {
                jdbc
                    .update(
                        "INSERT INTO exam_problem_results (exam_participant_id,problem_id,"
                            + "last_valid_exam_submission_id,score,version,created_at,updated_at) "
                            + "VALUES (?,?,?,?,0,?,?) ON DUPLICATE KEY UPDATE "
                            + "last_valid_exam_submission_id=VALUES(last_valid_exam_submission_id),"
                            + "score=VALUES(score),version=version+1,updated_at=VALUES(updated_at)",
                        participant.id(),
                        Long.parseLong(problem.problemId()),
                        latest.id(),
                        score,
                        now,
                        now
                    );
            }
            results
                .add(
                    new ProblemResult(
                        problem.problemId(),
                        latest == null ? null : Long.toString(latest.id()),
                        latest == null ? null : latest.verdict(),
                        score.setScale(2).toPlainString(),
                        latest == null ? null : latest.passed(),
                        latest == null ? null : latest.total(),
                        null,
                        null,
                        null,
                        entries.size(),
                        formatNullable(first),
                        formatNullable(firstAccepted),
                        firstAccepted == null ? null : Duration.between(first, firstAccepted).toMillis()
                    )
                );
        }
        return new Calculated(
            participant,
            submissions > 0 && !"ABSENT".equals(participant.status()),
            total,
            last,
            results
        );
    }

    private String formatNullable(LocalDateTime time) {
        return time == null ? null : format(time);
    }

    private String format(LocalDateTime time) {
        return DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx")
            .format(time.atZone(ClockConfiguration.SERVICE_ZONE));
    }

    /** 고정 문제의 회차와 배점이다. */
    public record Problem(String problemId, String problemRevisionId, String score) {
    }
    /** 참가자와 확정 문제별 결과다. */
    public record Item(
        String memberId,
        String participantId,
        boolean isRanked,
        Integer rank,
        String score,
        Integer solvedCount,
        Integer penaltyMinutes,
        List<ProblemResult> problems
    ) {
    }
    /** 코드와 숨김 테스트를 제외한 문제별 확정 및 분석 값이다. */
    public record ProblemResult(
        String problemId,
        String examSubmissionId,
        String verdict,
        String score,
        Integer passedCount,
        Integer totalCount,
        Boolean isSolved,
        Integer wrongCount,
        String acceptedAt,
        int submissionCount,
        String firstSubmittedAt,
        String firstAcceptedAt,
        Long timeToFirstAcceptedMs
    ) {
    }
    /** 결과 버전에 고정된 전체 스냅샷이다. */
    public record Snapshot(
        String context,
        String contextId,
        String resultStatus,
        String resultRevision,
        String finalizedAt,
        List<Problem> problems,
        List<Item> items
    ) {
    }
    /** 확정 알림에 필요한 참가자와 결과 버전이다. */
    public record Finalized(
        String context,
        String contextId,
        String examId,
        long resultRevision,
        String finalizedAt,
        boolean isRefinalized,
        List<String> targetMemberIds
    ) {
    }
    private record Participant(long id, long memberId, String status) {
    }
    /** 문제의 종결 접수다. */
    private record Submission(
        long id,
        String status,
        String verdict,
        BigDecimal score,
        Integer passed,
        Integer total,
        LocalDateTime receivedAt
    ) {
    }
    private record Calculated(
        Participant participant,
        boolean ranked,
        BigDecimal score,
        LocalDateTime lastSubmittedAt,
        List<ProblemResult> problems
    ) {
    }
}
