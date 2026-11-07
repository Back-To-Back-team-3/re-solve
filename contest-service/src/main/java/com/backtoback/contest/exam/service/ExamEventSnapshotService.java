package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.OffsetDateTime;

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

/** 시험 SSE가 공개할 현재 상태와 본인 접수 집계를 읽는다. */
@Service
@RequiredArgsConstructor
public class ExamEventSnapshotService {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 활성 참가자를 재검증하며 전체 종료 전에는 집계를 읽지 않는다. */
    @Transactional(readOnly = true)
    public Snapshot read(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        var parent = exams.findById(exam).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        var participant
            = participants
                .findByExamIdAndMemberIdAndStatusNot(exam, member, ExamParticipantStatus.CANCELED)
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
        var now = OffsetDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        if (parent.getStatus() != ExamStatus.CLOSED && parent.getStatus() != ExamStatus.FINALIZED) {
            return new Snapshot(examId, parent.getStatus().name(), parent.getResultRevision(), now, null, null);
        }
        var counts
            = jdbc
                .queryForMap(
                    "SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN status='JUDGED' "
                        + "OR (status='FAILED' AND is_zero_confirmed=true) THEN 1 ELSE 0 END),0) AS terminal "
                        + "FROM exam_submissions WHERE exam_participant_id=?",
                    participant.getId()
                );
        return new Snapshot(
            examId,
            parent.getStatus().name(),
            parent.getResultRevision(),
            now,
            ((Number)counts.get("terminal")).intValue(),
            ((Number)counts.get("total")).intValue()
        );
    }

    /** 양의 BIGINT 식별자를 확인한다. */
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

    /** 집계는 비공개 상태에서 null이다. */
    public record Snapshot(
        String examId,
        String status,
        long revision,
        OffsetDateTime serverNow,
        Integer terminalCount,
        Integer totalSubmissionCount
    ) {
    }
}
