package com.backtoback.contest.exam.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.StudyMembershipReplicaRepository;
import com.backtoback.contest.exam.service.ExamFinalizationService.Snapshot;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 시험 종료 전 공개 제한과 잠정·확정 결과의 원천을 구분한다. */
@Service
@RequiredArgsConstructor
public class ExamResultQueryService {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final StudyMembershipReplicaRepository memberships;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExamRankingCache cache;

    /** 본인 참가만 허용하며 전체 종료 전에는 결과를 숨긴다. */
    @Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
    )
    public Me me(String examId, String memberId) {
        long examNumber = id(examId);
        long member = id(memberId);
        var exam = exam(examNumber);
        var participant
            = participants
                .findByExamIdAndMemberIdAndStatusNot(examNumber, member, ExamParticipantStatus.CANCELED)
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
        if (!visible(exam)) {
            return new Me("HIDDEN", Long.toString(exam.getResultRevision()), null, null, false, null, List.of());
        }
        var progress = progress(participant.getId());
        if (exam.getStatus() == ExamStatus.FINALIZED) {
            var item
                = snapshot(exam)
                    .items()
                    .stream()
                    .filter(p -> p.participantId().equals(participant.getId().toString()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Participant missing from snapshot"));
            var results
                = item
                    .problems()
                    .stream()
                    .map(
                        p -> new Problem(
                            p.problemId(),
                            p.examSubmissionId(),
                            p.verdict(),
                            p.score(),
                            p.passedCount(),
                            p.totalCount(),
                            false
                        )
                    )
                    .toList();
            return new Me(
                "FINALIZED",
                Long.toString(exam.getResultRevision()),
                item.score(),
                item.rank(),
                item.isRanked(),
                progress,
                results
            );
        }
        var ranking = ranking(examNumber);
        var own = ranking.stream().filter(r -> r.participantId() == participant.getId()).findFirst().orElse(null);
        var problems
            = jdbc
                .query(
                    "SELECT p.problem_id,s.id,s.status,s.verdict,s.score,s.passed_count,s.total_count "
                        + "FROM exam_problems p LEFT JOIN exam_problem_results r ON r.problem_id=p.problem_id "
                        + "AND r.exam_participant_id=? LEFT JOIN exam_submissions s "
                        + "ON s.id=r.last_valid_exam_submission_id " + "WHERE p.exam_id=? ORDER BY p.display_order",
                    (rs, row) -> {
                        Long receipt = rs.getObject(2, Long.class);
                        String status = rs.getString(3);
                        boolean pending
                            = receipt != null && !"JUDGED".equals(status)
                                && !("FAILED".equals(status) && zeroConfirmed(receipt));
                        return new Problem(
                            Long.toString(rs.getLong(1)),
                            receipt == null ? null : receipt.toString(),
                            rs.getString(4),
                            money(rs.getBigDecimal(5)),
                            rs.getObject(6, Integer.class),
                            rs.getObject(7, Integer.class),
                            pending
                        );
                    },
                    participant.getId(),
                    examNumber
                );
        return new Me(
            "PROVISIONAL",
            Long.toString(exam.getResultRevision()),
            own == null ? "0.00" : own.score(),
            own == null ? null : own.rank(),
            own != null,
            progress,
            problems
        );
    }

    /** 등록자 또는 스터디 구성원에게 결과 버전에 묶인 순위 페이지를 반환한다. */
    @Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
    )
    public Page standings(String examId, String memberId, String after, int size) {
        long examNumber = id(examId);
        long member = id(memberId);
        if (size < 1 || size > 100) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var exam = exam(examNumber);
        if (participants
            .findByExamIdAndMemberIdAndStatusNot(examNumber, member, ExamParticipantStatus.CANCELED)
            .isEmpty() && !memberships.isActive(exam.getStudyId(), member)) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        if (!visible(exam)) {
            throw new ContestException(ContestErrorCode.EXAM_RESULT_NOT_AVAILABLE);
        }
        int offset = after == null ? 0 : cursor(after, examNumber, exam.getResultRevision());
        List<Standing> ranking = cache.read(examNumber, exam.getResultRevision());
        if (ranking == null) {
            ranking = standingsFromDatabase(exam);
        }
        if (offset > ranking.size()) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        int end = Math.min(ranking.size(), offset + size);
        var items
            = ranking
                .subList(offset, end)
                .stream()
                .map(
                    row -> new StandingItem(
                        row.memberId(),
                        nickname(row.memberId()),
                        row.score(),
                        row.rank(),
                        row.pending()
                    )
                )
                .toList();
        boolean more = end < ranking.size();
        return new Page(
            exam.getStatus() == ExamStatus.FINALIZED ? "FINALIZED" : "PROVISIONAL",
            Long.toString(exam.getResultRevision()),
            items,
            more ? encode(examNumber, exam.getResultRevision(), end) : null,
            more
        );
    }

    /** 동일 DB 스냅샷의 버전과 전체 순위를 캐시 복구 작업에 제공한다. */
    @Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
    )
    public CacheSnapshot cacheSnapshot(long examId) {
        var exam = exam(examId);
        if (!visible(exam)) {
            return null;
        }
        return new CacheSnapshot(exam.getResultRevision(), standingsFromDatabase(exam));
    }

    /** 확정 후에는 불변 스냅샷을, 이전에는 현재 집계를 사용한다. */
    private List<Standing> standingsFromDatabase(Exam exam) {
        if (exam.getStatus() == ExamStatus.FINALIZED) {
            return snapshot(exam)
                .items()
                .stream()
                .filter(ExamFinalizationService.Item::isRanked)
                .map(
                    item -> new Standing(
                        Long.parseLong(item.participantId()),
                        item.memberId(),
                        null,
                        item.score(),
                        item.rank(),
                        false
                    )
                )
                .toList();
        }
        return ranking(exam.getId());
    }

    /** DB 점수와 마지막 접수 시각으로 공동 순위를 계산한다. */
    private List<Standing> ranking(long examId) {
        var rows
            = jdbc
                .query(
                    "SELECT p.id,p.member_id,p.total_score,p.last_valid_submitted_at,m.nickname,"
                        + "EXISTS(SELECT 1 FROM exam_problem_results r JOIN exam_submissions s "
                        + "ON s.id=r.last_valid_exam_submission_id WHERE r.exam_participant_id=p.id "
                        + "AND (s.status IN ('ACCEPTED','REQUESTED') "
                        + "OR (s.status='FAILED' AND s.is_zero_confirmed=false))) "
                        + "FROM exam_participants p LEFT JOIN member_replicas m ON m.member_id=p.member_id "
                        + "WHERE p.exam_id=? AND p.status NOT IN ('CANCELED','ABSENT') AND p.is_ranked=true",
                    (rs, row) -> new RankingRow(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getBigDecimal(3),
                        rs.getObject(4, LocalDateTime.class),
                        rs.getString(5),
                        rs.getBoolean(6)
                    ),
                    examId
                );
        rows
            .sort(
                Comparator
                    .comparing(RankingRow::score, Comparator.reverseOrder())
                    .thenComparing(RankingRow::last, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingLong(RankingRow::member)
            );
        var result = new ArrayList<Standing>();
        RankingRow previous = null;
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (previous == null || previous.score().compareTo(row.score()) != 0
                || !Objects.equals(previous.last(), row.last())) {
                rank = i + 1;
            }
            result
                .add(
                    new Standing(
                        row.participant(),
                        Long.toString(row.member()),
                        row.nickname() == null ? "탈퇴한 사용자" : row.nickname(),
                        money(row.score()),
                        rank,
                        row.pending()
                    )
                );
            previous = row;
        }
        return result;
    }

    /** 확정 버전의 불변 스냅샷을 읽는다. */
    private Snapshot snapshot(Exam exam) {
        var json
            = jdbc
                .queryForObject(
                    "SELECT payload FROM result_snapshots WHERE context='EXAM' AND context_id=? "
                        + "AND result_revision=?",
                    String.class,
                    exam.getId(),
                    exam.getResultRevision()
                );
        return mapper.readValue(json, Snapshot.class);
    }

    /** 회원 복제본의 표시 이름을 읽는다. */
    private String nickname(String memberId) {
        return jdbc
            .query(
                "SELECT nickname FROM member_replicas WHERE member_id=?",
                (rs, row) -> rs.getString(1),
                Long.parseLong(memberId)
            )
            .stream()
            .findFirst()
            .orElse("탈퇴한 사용자");
    }

    /** 본인 접수의 종결 수와 전체 수다. */
    private Progress progress(long participantId) {
        return jdbc
            .queryForObject(
                "SELECT COUNT(*),COALESCE(SUM(CASE WHEN status='JUDGED' "
                    + "OR (status='FAILED' AND is_zero_confirmed=true) THEN 1 ELSE 0 END),0) "
                    + "FROM exam_submissions WHERE exam_participant_id=?",
                (rs, row) -> new Progress(rs.getInt(2), rs.getInt(1)),
                participantId
            );
    }

    /** 실패 접수의 관리자 종결 여부를 확인한다. */
    private boolean zeroConfirmed(long receipt) {
        return Boolean.TRUE
            .equals(
                jdbc.queryForObject("SELECT is_zero_confirmed FROM exam_submissions WHERE id=?", Boolean.class, receipt)
            );
    }

    /** 시험을 조회한다. */
    private Exam exam(long id) {
        return exams.findById(id).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
    }

    /** 시험 전체 종료 이후에만 결과를 공개한다. */
    private boolean visible(Exam exam) {
        return exam.getStatus() == ExamStatus.CLOSED || exam.getStatus() == ExamStatus.FINALIZED;
    }

    /** 점수를 문자열로 변환한다. */
    private String money(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** 시험·결과 버전·위치로 다음 페이지 커서를 만든다. */
    private String encode(long exam, long revision, int offset) {
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString((exam + ":" + revision + ":" + offset).getBytes(StandardCharsets.UTF_8));
    }

    /** 커서 형식과 현재 결과 버전을 확인한다. */
    private int cursor(String value, long exam, long revision) {
        try {
            var parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 3 || Long.parseLong(parts[0]) != exam || Integer.parseInt(parts[2]) < 0) {
                throw new IllegalArgumentException();
            }
            if (Long.parseLong(parts[1]) != revision) {
                throw new ContestException(ContestErrorCode.RESULT_CURSOR_EXPIRED);
            }
            return Integer.parseInt(parts[2]);
        } catch (IllegalArgumentException failure) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /** 양의 식별자를 검증한다. */
    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /** 본인 결과와 공개 가능한 문제별 결과다. */
    public record Me(
        String resultStatus,
        String resultRevision,
        String score,
        Integer rank,
        boolean isRanked,
        Progress progress,
        List<Problem> problems
    ) {
    }
    /** 본인 접수의 종결 현황이다. */
    public record Progress(int terminalCount, int totalSubmissionCount) {
    }
    /** 문제별 마지막 유효 접수 결과다. */
    public record Problem(
        String problemId,
        String lastValidExamSubmissionId,
        String verdict,
        String score,
        Integer passedCount,
        Integer totalCount,
        boolean pending
    ) {
    }
    /** 순위 페이지와 커서다. */
    public record Page(
        String resultStatus,
        String resultRevision,
        List<StandingItem> items,
        String nextCursor,
        boolean hasMore
    ) {
    }
    /** 외부에 공개하는 순위 항목이다. */
    public record StandingItem(String memberId, String nickname, String score, Integer rank, boolean pending) {
    }
    /** 정렬 전 DB 행이다. */
    private record RankingRow(
        long participant,
        long member,
        BigDecimal score,
        LocalDateTime last,
        String nickname,
        boolean pending
    ) {
    }
    /** 내부 참가 식별자가 포함된 순위다. */
    public record Standing(
        long participantId,
        String memberId,
        String nickname,
        String score,
        Integer rank,
        boolean pending
    ) {
    }
    /** 캐시를 재생성할 때 사용한 결과 버전이다. */
    public record CacheSnapshot(long revision, List<Standing> items) {
    }
}
