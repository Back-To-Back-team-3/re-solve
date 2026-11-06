package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.api.PageResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.ExamDetailResponse;
import com.backtoback.contest.exam.dto.ExamListResponse;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.StudyMembershipReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 스터디 구성원과 활성 참가자의 시험 조회 범위를 제한한다 (API 명세서 4.1.2). */
@Service
@RequiredArgsConstructor
public class ExamQueryService {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamProblemRepository problems;
    private final StudyMembershipReplicaRepository memberships;
    private final Clock clock;

    /**
     * 현재 구성원이나 활성 참가자에게 부수 효과 없이 시험 상세를 반환한다 (API 명세서 4.1.3).
     * 잘못된 식별자는 {@code COMMON_INVALID_REQUEST}, 없는 시험은 {@code EXAM_NOT_FOUND},
     * 권한이 없으면 {@code AUTH_ACCESS_DENIED}로 거절한다.
     */
    @Transactional(readOnly = true)
    public ExamDetailResponse detail(String examId, String memberId) {
        long examNumber = id(examId);
        long member = id(memberId);
        var exam = exams.findById(examNumber).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        var participant
            = participants
                .findByExamIdAndMemberIdAndStatusNot(examNumber, member, ExamParticipantStatus.CANCELED)
                .orElse(null);
        if (participant == null && !memberships.isActive(exam.getStudyId(), member)) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var now
            = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
        return ExamDetailResponse.from(exam, problems.countByExamId(examNumber), participant, now);
    }

    /**
     * 권한으로 시험 범위를 제한한 뒤 상태 필터와 페이지를 적용한다 (API 명세서 4.1.2).
     * 잘못된 입력은 {@code COMMON_INVALID_REQUEST}, 구성원이 아니고 활성 참가도 없으면
     * {@code AUTH_ACCESS_DENIED}로 거절한다.
     */
    @Transactional(readOnly = true)
    public PageResponse<ExamListResponse> list(
        String studyId,
        String memberId,
        String status,
        int page,
        int size,
        String sort
    ) {
        long study = id(studyId);
        long member = id(memberId);
        ExamStatus filter;
        try {
            filter = status == null ? null : ExamStatus.valueOf(status);
        } catch (IllegalArgumentException failure) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        String[] parts = sort.split(",", -1);
        if (page < 0 || size < 1 || size > 100 || parts.length != 2
            || !(parts[0].equals("startsAt") || parts[0].equals("createdAt"))
            || !(parts[1].equals("asc") || parts[1].equals("desc"))) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        boolean approved = memberships.isActive(study, member);
        if (!approved && !participants.hasActiveInStudy(study, member)) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        Sort ordering = Sort.by(Sort.Direction.fromString(parts[1]), parts[0]).and(Sort.by("id"));
        var result = exams.findVisible(study, member, approved, filter, PageRequest.of(page, size, ordering));
        return PageResponse
            .from(
                result
                    .map(
                        exam -> ExamListResponse
                            .from(
                                exam,
                                problems.countByExamId(exam.getId()),
                                participants
                                    .findByExamIdAndMemberIdAndStatusNot(
                                        exam.getId(),
                                        member,
                                        ExamParticipantStatus.CANCELED
                                    )
                                    .orElse(null)
                            )
                    )
            );
    }

    /** 양의 BIGINT 식별자만 허용한다. */
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
}
