package com.backtoback.study.hint.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.hint.domain.HintBlockContextType;
import com.backtoback.study.hint.domain.HintBlockReplica;

public interface HintBlockReplicaRepository extends JpaRepository<HintBlockReplica, Long> {

    @Modifying
    @Query(
        nativeQuery = true,
        value = "INSERT IGNORE INTO hint_block_replicas"
            + " (member_id, context_type, context_id, problem_id, starts_at, ends_at, context_revision, created_at, updated_at)"
            + " VALUES (:memberId, :contextType, :contextId, :problemId, :startsAt, :endsAt, :contextRevision, now(6), now(6))"
    )
    void insertIgnore(
        @Param("memberId") Long memberId,
        @Param("contextType") String contextType,
        @Param("contextId") Long contextId,
        @Param("problemId") Long problemId,
        @Param("startsAt") LocalDateTime startsAt,
        @Param("endsAt") LocalDateTime endsAt,
        @Param("contextRevision") int contextRevision
    );

    @Query(
        "SELECT MAX(r.contextRevision) FROM HintBlockReplica r"
            + " WHERE r.contextType = :contextType AND r.contextId = :contextId"
    )
    Optional<Integer> findMaxContextRevisionByContextTypeAndContextId(
        @Param("contextType") HintBlockContextType contextType,
        @Param("contextId") Long contextId
    );

    @Modifying
    @Query(
        "DELETE FROM HintBlockReplica r"
            + " WHERE r.memberId = :memberId AND r.contextType = :contextType AND r.contextId = :contextId"
    )
    void deleteByMemberIdAndContextTypeAndContextId(
        @Param("memberId") Long memberId,
        @Param("contextType") HintBlockContextType contextType,
        @Param("contextId") Long contextId
    );

    @Modifying
    @Query("DELETE FROM HintBlockReplica r WHERE r.contextType = :contextType AND r.contextId = :contextId")
    void deleteByContextTypeAndContextId(
        @Param("contextType") HintBlockContextType contextType,
        @Param("contextId") Long contextId
    );

    @Modifying
    @Query("DELETE FROM HintBlockReplica r WHERE r.memberId = :memberId")
    void deleteByMemberId(@Param("memberId") Long memberId);
}
