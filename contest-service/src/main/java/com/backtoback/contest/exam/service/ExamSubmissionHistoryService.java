package com.backtoback.contest.exam.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 시험 참가자의 본인 접수 이력을 공개 시점과 결과 버전에 맞춰 반환한다. */
@Service
@RequiredArgsConstructor
public class ExamSubmissionHistoryService {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final JdbcTemplate jdbc;

    /** 커서의 시험·참가자·문제와 결과 버전을 확인하고 본인 이력만 읽는다. */
    @Transactional(readOnly = true)
    public Page read(String examId, String memberId, String problemId, String after, int size) {
        long exam = id(examId);
        long member = id(memberId);
        Long problem = problemId == null ? null : id(problemId);
        if (size < 1 || size > 100) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var parent = exams.findById(exam).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        var participant
            = participants
                .findByExamIdAndMemberIdAndStatusNot(exam, member, ExamParticipantStatus.CANCELED)
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
        long cursorId = 0;
        if (after != null) {
            var cursor = decode(after);
            if (cursor.exam() != exam || cursor.participant() != participant.getId()
                || cursor.problem() != (problem == null ? 0 : problem)) {
                throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
            if (cursor.revision() != parent.getResultRevision()) {
                throw new ContestException(ContestErrorCode.RESULT_CURSOR_EXPIRED);
            }
            cursorId = cursor.id();
        }
        boolean visible = parent.getStatus() == ExamStatus.CLOSED || parent.getStatus() == ExamStatus.FINALIZED;
        var rows
            = jdbc
                .query(
                    "SELECT id,problem_id,draft_seq,submission_type,received_at,status,verdict,score,"
                        + "passed_count,total_count FROM exam_submissions "
                        + "WHERE exam_participant_id=? AND (?=0 OR id<?) "
                        + "AND (? IS NULL OR problem_id=?) ORDER BY id DESC LIMIT ?",
                    (rs, row) -> new Item(
                        Long.toString(rs.getLong(1)),
                        Long.toString(rs.getLong(2)),
                        Long.toString(rs.getLong(3)),
                        rs.getString(4),
                        rs.getObject(5, LocalDateTime.class).atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
                        publicStatus(rs.getString(6), visible),
                        visible ? rs.getString(7) : null,
                        visible ? score(rs.getBigDecimal(8)) : null,
                        visible ? rs.getObject(9, Integer.class) : null,
                        visible ? rs.getObject(10, Integer.class) : null
                    ),
                    participant.getId(),
                    cursorId,
                    cursorId,
                    problem,
                    problem,
                    size + 1
                );
        boolean more = rows.size() > size;
        List<Item> items = more ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows);
        String next
            = more ? encode(
                new Cursor(
                    exam,
                    participant.getId(),
                    problem == null ? 0 : problem,
                    parent.getResultRevision(),
                    Long.parseLong(items.getLast().examSubmissionId())
                )
            ) : null;
        return new Page(items, next, more);
    }

    /** 종료 전에는 판정 종결을 요청 상태로 표시한다. */
    private String publicStatus(String status, boolean visible) {
        return !visible && "JUDGED".equals(status) ? "REQUESTED" : status;
    }

    private String score(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** 결과 버전과 마지막 접수 ID를 불투명 문자열로 만든다. */
    private String encode(Cursor cursor) {
        String value
            = cursor.exam() + ":" + cursor.participant() + ":" + cursor.problem() + ":" + cursor.revision() + ":"
                + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String encoded) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 5) {
                throw new IllegalArgumentException();
            }
            var cursor
                = new Cursor(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]),
                    Long.parseLong(parts[4])
                );
            if (cursor.exam() <= 0 || cursor.participant() <= 0 || cursor.problem() < 0 || cursor.revision() < 0
                || cursor.id() <= 0) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (IllegalArgumentException failure) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

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

    /** 본인 접수 이력과 다음 커서다. */
    public record Page(List<Item> items, String nextCursor, boolean hasMore) {
    }
    /** 공개 시점에 따라 결과가 null일 수 있는 접수 항목이다. */
    public record Item(
        String examSubmissionId,
        String problemId,
        String draftSeq,
        String submissionType,
        OffsetDateTime receivedAt,
        String status,
        String verdict,
        String score,
        Integer passedCount,
        Integer totalCount
    ) {
    }
    private record Cursor(long exam, long participant, long problem, long revision, long id) {
    }
}
