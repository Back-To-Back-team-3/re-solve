package com.backtoback.study.assignment.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.assignment.client.ProblemSummaryItem;
import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.dto.AssignmentCreateRequest;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.global.error.StudyException;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 문제집 생성 저장 전용 서비스. 트랜잭션 경계를 분리해 Problem API 호출 중 DB 커넥션을 점유하지 않는다.
 */
@Service
@RequiredArgsConstructor
class AssignmentPersistService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentProblemRepository assignmentProblemRepository;
    private final ObjectMapper objectMapper;

    /**
     * Assignment와 AssignmentProblem 목록을 단일 트랜잭션으로 저장한다(ERD §4.5.5·§4.5.6).
     *
     * @return 생성된 assignmentId
     */
    @Transactional
    public Long persist(Long studyId, AssignmentCreateRequest request, Map<Long, ProblemSummaryItem> summaryMap) {
        boolean preSolvedAllowed = Boolean.TRUE.equals(request.isPreSolvedAllowed());
        LocalDateTime startsAt
            = request.startsAt().atZoneSameInstant(ClockConfiguration.SERVICE_ZONE).toLocalDateTime();
        LocalDateTime deadlineAt
            = request.deadlineAt().atZoneSameInstant(ClockConfiguration.SERVICE_ZONE).toLocalDateTime();
        Assignment assignment
            = Assignment
                .schedule(
                    studyId,
                    request.title(),
                    request.tagId(),
                    request.goal(),
                    preSolvedAllowed,
                    startsAt,
                    deadlineAt
                );
        assignmentRepository.save(assignment);

        List<Long> problemIds = request.problemIds();
        List<AssignmentProblem> problems = new ArrayList<>(problemIds.size());
        for (int i = 0; i < problemIds.size(); i++) {
            Long problemId = problemIds.get(i);
            ProblemSummaryItem item = summaryMap.get(problemId);
            problems
                .add(
                    AssignmentProblem
                        .assign(
                            assignment.getId(),
                            problemId,
                            item.problemRevisionId(),
                            item.revisionNumber(),
                            item.title(),
                            item.difficulty(),
                            serializeTags(item.tags()),
                            i + 1
                        )
                );
        }
        assignmentProblemRepository.saveAll(problems);
        return assignment.getId();
    }

    private String serializeTags(List<ProblemSummaryItem.TagItem> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JacksonException exception) {
            throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }
}
