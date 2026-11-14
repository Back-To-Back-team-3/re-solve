package com.backtoback.contest.exam.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.dto.ActiveParticipationResponse;
import com.backtoback.contest.exam.dto.ExamRejudgeEligibilityResponse;
import com.backtoback.contest.exam.dto.HintBlockResponse;
import com.backtoback.contest.exam.dto.ProblemUsageResponse;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 자원 작업과 힌트 복구에 필요한 시험 상태를 조회한다 (API 명세서 §4.7). */
@Service
@RequiredArgsConstructor
public class ExamResourceQueryService {
    private final JdbcTemplate jdbc;
    private final ExamRepository exams;
    private final Clock clock;

    /** 실제 입장한 미확정 시험만 탈퇴 차단 대상으로 반환한다. */
    @Transactional(readOnly = true)
    public ActiveParticipationResponse active(String memberId) {
        var rows
            = jdbc
                .query(
                    "SELECT p.exam_id,p.id,p.entered_at,p.status FROM exam_participants p "
                        + "JOIN exams e ON e.id=p.exam_id WHERE p.member_id=? AND p.entered_at IS NOT NULL "
                        + "AND p.status<>'ABSENT' AND e.status NOT IN ('FINALIZED','CANCELED') ORDER BY p.id",
                    (rs, row) -> new ActiveParticipationResponse.Item(
                        "EXAM",
                        rs.getString(1),
                        rs.getString(2),
                        rs.getObject(3, LocalDateTime.class).atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
                        rs.getString(4)
                    ),
                    id(memberId)
                );
        return new ActiveParticipationResponse(!rows.isEmpty(), rows);
    }

    /** 예정 또는 시간상 진행 중인 시험의 고정 문제 사용을 반환한다. */
    @Transactional(readOnly = true)
    public ProblemUsageResponse usage(String problemId) {
        var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        var rows
            = jdbc
                .query(
                    "SELECT e.id,p.problem_revision_id,e.status,e.starts_at,e.ends_at FROM exams e "
                        + "JOIN exam_problems p ON p.exam_id=e.id WHERE p.problem_id=? "
                        + "AND (e.status='SCHEDULED' OR (e.status='IN_PROGRESS' AND e.ends_at>=?)) ORDER BY e.id",
                    (rs, row) -> new ProblemUsageResponse.Item(
                        "EXAM",
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getObject(4, LocalDateTime.class).atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
                        rs.getObject(5, LocalDateTime.class).atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
                    ),
                    id(problemId),
                    now
                );
        return new ProblemUsageResponse(rows, !rows.isEmpty());
    }

    /** 개인 종료와 관계없이 전체 종료 전인 활성 등록자의 차단 상태를 제공한다. */
    @Transactional(readOnly = true)
    public HintBlockResponse hintBlocks(String memberId, String after, int size) {
        long member = id(memberId);
        if (size < 1 || size > 100) {
            throw invalid();
        }
        long last = after == null ? 0 : cursor(after, member);
        var rows
            = jdbc
                .query(
                    "SELECT p.id,p.exam_id,p.participant_revision,e.exam_revision,e.starts_at,e.ends_at "
                        + "FROM exam_participants p JOIN exams e ON e.id=p.exam_id WHERE p.member_id=? AND p.id>? "
                        + "AND p.status<>'CANCELED' AND e.status NOT IN ('CANCELED','CLOSED','FINALIZED') "
                        + "ORDER BY p.id LIMIT ?",
                    (rs, row) -> new HintRow(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getLong(4),
                        rs.getObject(5, LocalDateTime.class),
                        rs.getObject(6, LocalDateTime.class)
                    ),
                    member,
                    last,
                    size + 1
                );
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        var items
            = page
                .stream()
                .map(
                    value -> new HintBlockResponse.Item(
                        "EXAM",
                        Long.toString(value.exam()),
                        Long.toString(value.participant()),
                        Long.toString(member),
                        Long.toString(value.participantRevision()),
                        Long.toString(value.examRevision()),
                        jdbc
                            .queryForList(
                                "SELECT CAST(problem_id AS CHAR) FROM exam_problems "
                                    + "WHERE exam_id=? ORDER BY display_order",
                                String.class,
                                value.exam()
                            ),
                        value.starts().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
                        value.ends().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
                    )
                )
                .toList();
        return new HintBlockResponse(items, more ? encode(member, page.getLast().participant()) : null, more);
    }

    /** 이 조회는 재채점 요청이나 시험 결과 확정을 잠그지 않는다. */
    @Transactional(readOnly = true)
    public ExamRejudgeEligibilityResponse eligibility(String examId) {
        var exam = exams.findById(id(examId)).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        String status = exam.getStatus().name();
        return new ExamRejudgeEligibilityResponse(
            examId,
            status,
            "IN_PROGRESS".equals(status) || "CLOSED".equals(status)
        );
    }

    private long cursor(String value, long member) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String prefix = "hint:" + member + ":";
            if (!decoded.startsWith(prefix)) {
                throw new IllegalArgumentException();
            }
            long result = id(decoded.substring(prefix.length()));
            if (!encode(member, result).equals(value)) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private String encode(long member, long participant) {
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(("hint:" + member + ":" + participant).getBytes(StandardCharsets.UTF_8));
    }

    private long id(String value) {
        try {
            if (value != null && value.matches("[1-9][0-9]*")) {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException ignored) {
            // 저장 가능한 양의 BIGINT만 허용한다.
        }
        throw invalid();
    }

    private ContestException invalid() {
        return new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    private record HintRow(
        long participant,
        long exam,
        long participantRevision,
        long examRevision,
        LocalDateTime starts,
        LocalDateTime ends
    ) {
    }
}
