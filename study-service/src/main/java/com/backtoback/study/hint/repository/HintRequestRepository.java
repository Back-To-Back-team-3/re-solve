package com.backtoback.study.hint.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;

public interface HintRequestRepository extends JpaRepository<HintRequest, Long> {

    @Query(
        "SELECT COUNT(r) > 0 FROM HintRequest r" + " WHERE r.memberId = :memberId AND r.problemId = :problemId"
            + " AND r.level = :prevLevel AND r.status IN :readyStatuses"
    )
    boolean existsByMemberIdAndProblemIdAndLevelAndStatusIn(
        @Param("memberId") Long memberId,
        @Param("problemId") Long problemId,
        @Param("prevLevel") int prevLevel,
        @Param("readyStatuses") List<HintRequestStatus> readyStatuses
    );

    Optional<HintRequest> findByMemberIdAndProblemRevisionIdAndLevel(Long memberId, Long problemRevisionId, int level);

    /**
     * 회원의 문제별 전체 힌트 요청 이력을 단계 오름차순·요청 시각 오름차순으로 반환한다 (api-spec §6.1.2).
     * 모든 회차와 모든 상태를 포함한다.
     */
    List<HintRequest> findAllByMemberIdAndProblemIdOrderByLevelAscCreatedAtAsc(Long memberId, Long problemId);

    /**
     * 단건 조회 — memberId 조건으로 본인 요청 여부를 DB 레벨에서 확인한다 (api-spec §6.1.3).
     */
    Optional<HintRequest> findByIdAndMemberId(Long id, Long memberId);

    Optional<HintRequest> findByMemberIdAndProblemIdAndStatus(Long memberId, Long problemId, HintRequestStatus status);

    /**
     * 조건부 완료 갱신 — {@code expectedStatus}인 행만 갱신하여 경쟁 처리를 방지한다 (event-contract §S8).
     * 반환값 0이면 이미 다른 상태로 전이됐거나 존재하지 않는 행이다.
     */
    @Modifying
    @Query(
        "UPDATE HintRequest r" + " SET r.status = :newStatus, r.content = :content, r.completedAt = :completedAt,"
            + " r.cached = :isCached, r.fallback = :isFallback" + " WHERE r.id = :id AND r.status = :expectedStatus"
    )
    int completeIfGenerating(
        @Param("id") Long id,
        @Param("newStatus") HintRequestStatus newStatus,
        @Param("content") String content,
        @Param("completedAt") LocalDateTime completedAt,
        @Param("isCached") boolean isCached,
        @Param("isFallback") boolean isFallback,
        @Param("expectedStatus") HintRequestStatus expectedStatus
    );

    /**
     * 조건부 차단 갱신 — GENERATING 행만 BLOCKED로 전이하여 경쟁 처리를 방지한다 (event-contract §C8).
     * 반환값 0이면 이미 다른 상태로 전이됐거나 존재하지 않는 행이다.
     */
    @Modifying
    @Query(
        "UPDATE HintRequest r SET r.status = 'BLOCKED', r.blockedReason = :blockedReason,"
            + " r.completedAt = :completedAt WHERE r.id = :id AND r.status = 'GENERATING'"
    )
    int blockIfGeneratingById(
        @Param("id") Long id,
        @Param("blockedReason") String blockedReason,
        @Param("completedAt") LocalDateTime completedAt
    );
}
