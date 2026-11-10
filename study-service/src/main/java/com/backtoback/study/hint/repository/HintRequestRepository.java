package com.backtoback.study.hint.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
        @Param("readyStatuses") java.util.List<HintRequestStatus> readyStatuses
    );

    Optional<HintRequest> findByMemberIdAndProblemRevisionIdAndLevel(Long memberId, Long problemRevisionId, int level);
}
