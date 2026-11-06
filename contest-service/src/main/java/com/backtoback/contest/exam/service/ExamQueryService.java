package com.backtoback.contest.exam.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.api.PageResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.ExamListResponse;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.StudyMembershipReplicaRepository;
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

    /** 허용된 시험에 필터와 페이지를 적용한다. */
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
