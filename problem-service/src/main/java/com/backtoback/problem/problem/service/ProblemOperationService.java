package com.backtoback.problem.problem.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemStateOperation;
import com.backtoback.problem.problem.repository.ProblemStateOperationRepository;

import lombok.RequiredArgsConstructor;

/** 원래 상태 작업의 영속 스냅샷을 조회한다. */
@Service
@RequiredArgsConstructor
public class ProblemOperationService {
    private final ProblemStateOperationRepository operations;

    /**
     * 문제 소속이 일치하는 원래 작업 결과를 조회한다.
     *
     * @param problemId 대상 문제
     * @param operationId 원래 작업 ID
     * @return 작업 당시의 상태와 버전
     * @throws ProblemException 잘못된 입력은 {@code COMMON_INVALID_REQUEST},
     *     문제 소속이 일치하는 작업이 없으면 {@code PROBLEM_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public ProblemStateOperation find(long problemId, String operationId) {
        if (problemId <= 0 || operationId == null || operationId.isBlank() || operationId.length() > 100) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return operations
            .findByProblemIdAndOperationId(problemId, operationId)
            .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
    }
}
