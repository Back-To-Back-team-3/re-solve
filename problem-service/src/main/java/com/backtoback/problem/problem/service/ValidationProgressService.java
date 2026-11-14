package com.backtoback.problem.problem.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeProgressClient;
import com.backtoback.problem.problem.dto.response.AdminValidationProgressResponse;

import lombok.RequiredArgsConstructor;

/** 검증 요청에 연결된 Judge 진행 정보를 DB 트랜잭션 밖에서 조회한다. */
@Service
@RequiredArgsConstructor
public class ValidationProgressService {
    private final ValidationProgressStore store;
    private final JudgeProgressClient judge;

    /**
     * 원격 작업 상태와 Problem의 반영 여부를 결합한다.
     *
     * @param id 검증 ID
     * @param author 작성 관리자
     * @return 테스트별 판정과 사용 회차
     * @throws ProblemException Judge 연결·응답 불일치 시 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminValidationProgressResponse read(long id, long author) {
        var local = store.read(id, author);
        return AdminValidationProgressResponse
            .from(local, judge.read(local.id(), local.revisionId(), local.language()));
    }
}
