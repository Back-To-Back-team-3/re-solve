package com.backtoback.problem.problem.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.problem.problem.domain.ProblemStateOperation;

/** 문제 잠금으로 직렬화한 상태 요청과 원래 작업을 조회한다. */
public interface ProblemStateOperationRepository extends JpaRepository<ProblemStateOperation, Long> {
    /**
     * 문제와 작성자·종류·키로 최초 요청을 조회한다.
     *
     * @param actorId 작성 관리자
     * @param problemId 문제
     * @param operationType 작업 종류
     * @param idempotencyKey 멱등키
     * @return 같은 원래 작업
     */
    Optional<ProblemStateOperation> findByActorIdAndProblemIdAndOperationTypeAndIdempotencyKey(
        Long actorId,
        Long problemId,
        String operationType,
        String idempotencyKey
    );

    /**
     * 문제의 미종결 상태 작업 존재 여부를 확인한다.
     *
     * @param problemId 잠근 문제
     * @param state 조회할 작업 상태
     * @return 해당 상태의 작업 존재 여부
     */
    boolean existsByProblemIdAndState(Long problemId, String state);

    /**
     * 문제 소속을 함께 확인해 원래 작업을 조회한다.
     *
     * @param problemId 문제
     * @param operationId 작업 ID
     * @return 소속이 일치하는 작업
     */
    Optional<ProblemStateOperation> findByProblemIdAndOperationId(Long problemId, String operationId);
}
