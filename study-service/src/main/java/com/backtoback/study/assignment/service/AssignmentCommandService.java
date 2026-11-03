package com.backtoback.study.assignment.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.assignment.client.ProblemSummariesClient;
import com.backtoback.study.assignment.client.ProblemSummariesResponse;
import com.backtoback.study.assignment.client.ProblemSummaryItem;
import com.backtoback.study.assignment.dto.AssignmentCreateRequest;
import com.backtoback.study.assignment.dto.AssignmentCreateResponse;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;

import lombok.RequiredArgsConstructor;

/**
 * 문제집 생성 명령 서비스 (API 명세서 §5.4.1).
 * Problem API 호출은 커넥션 점유 방지를 위해 트랜잭션 밖에서 수행하고, 저장은 {@link AssignmentPersistService}에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class AssignmentCommandService {

    private final StudyRepository studyRepository;
    private final StudyMembershipRepository membershipRepository;
    private final ProblemSummariesClient problemSummariesClient;
    private final AssignmentPersistService assignmentPersistService;

    /**
     * 5.4.1 문제집 생성 — LEADER·MANAGER만 호출할 수 있다.
     *
     * @throws StudyException STUDY_NOT_FOUND, STUDY_ALREADY_CLOSED, AUTH_ACCESS_DENIED,
     *     STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE, COMMON_DEPENDENCY_UNAVAILABLE
     */
    public AssignmentCreateResponse createAssignment(
        Long studyId,
        Long requestMemberId,
        AssignmentCreateRequest request
    ) {
        var study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        if (study.getStatus() == StudyStatus.CLOSED) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }

        var requester
            = membershipRepository
                .findByStudyIdAndMemberIdAndStatusIn(studyId, requestMemberId, List.of(MembershipStatus.APPROVED))
                .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));
        if (requester.getRole() != MembershipRole.LEADER && requester.getRole() != MembershipRole.MANAGER) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }

        // Problem API 호출 — 트랜잭션 밖에서 수행해 DB 커넥션을 점유하지 않는다.
        ProblemSummariesResponse summaries = problemSummariesClient.summaries(request.problemIds());
        Map<Long, ProblemSummaryItem> summaryMap
            = summaries.items().stream().collect(Collectors.toMap(ProblemSummaryItem::problemId, Function.identity()));
        for (Long problemId : request.problemIds()) {
            ProblemSummaryItem item = summaryMap.get(problemId);
            if (item == null || !item.isAvailable()) {
                throw new StudyException(StudyErrorCode.STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE);
            }
        }

        Long assignmentId = assignmentPersistService.persist(studyId, request, summaryMap);
        return new AssignmentCreateResponse(String.valueOf(assignmentId), "SCHEDULED");
    }
}
