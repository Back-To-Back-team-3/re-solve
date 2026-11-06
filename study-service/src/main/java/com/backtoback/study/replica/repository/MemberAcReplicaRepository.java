package com.backtoback.study.replica.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.replica.domain.MemberAcReplica;

import jakarta.persistence.LockModeType;

public interface MemberAcReplicaRepository extends JpaRepository<MemberAcReplica, Long> {

    /**
     * 제출 ID로 AC 복제본을 조회한다(SubmissionJudged 소비 시 케이스 판별에 사용).
     */
    Optional<MemberAcReplica> findBySubmissionId(Long submissionId);

    /**
     * 문제별 AC 중 earliest submitted_at 순으로 정렬한 결과를 반환한다.
     * 호출자가 toMap으로 문제별 earliest 행을 뽑는다(ERD 4.5.9 인덱스 활용).
     */
    @Query(
        "SELECT m FROM MemberAcReplica m "
            + "WHERE m.memberId = :memberId AND m.problemId IN :problemIds AND m.accepted = true "
            + "ORDER BY m.problemId ASC, m.submittedAt ASC"
    )
    List<MemberAcReplica> findAllAcceptedByMemberIdAndProblemIds(
        @Param("memberId") Long memberId,
        @Param("problemIds") List<Long> problemIds
    );

    /**
     * 사전 풀이 허용 시 근거 AC를 반환한다. 접수 시각이 가장 이른 행 1개(ERD 4.5.9 IDX 활용).
     * {@code Pageable}로 1건만 반환하므로 {@code PageRequest.of(0, 1)}을 전달한다.
     */
    @Query(
        "SELECT m FROM MemberAcReplica m "
            + "WHERE m.memberId = :memberId AND m.problemId = :problemId AND m.accepted = true "
            + "ORDER BY m.submittedAt ASC"
    )
    List<MemberAcReplica> findEarliestBasisAc(
        @Param("memberId") Long memberId,
        @Param("problemId") Long problemId,
        Pageable pageable
    );

    /**
     * 사전 풀이 불허 시 근거 AC를 반환한다. startsAt 이후 중 접수 시각이 가장 이른 행 1개(ERD 4.5.9 IDX 활용).
     * {@code Pageable}로 1건만 반환하므로 {@code PageRequest.of(0, 1)}을 전달한다.
     */
    @Query(
        "SELECT m FROM MemberAcReplica m "
            + "WHERE m.memberId = :memberId AND m.problemId = :problemId AND m.accepted = true "
            + "AND m.submittedAt >= :startsAt ORDER BY m.submittedAt ASC"
    )
    List<MemberAcReplica> findEarliestBasisAcAfter(
        @Param("memberId") Long memberId,
        @Param("problemId") Long problemId,
        @Param("startsAt") LocalDateTime startsAt,
        Pageable pageable
    );

    /**
     * 잠금 읽기(FOR SHARE)로 AC 복제본을 조회한다.
     * REPEATABLE READ 스냅샷 회피를 위해 사용한다(스케줄러 §5.2 동시성 설계 포인트).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
        "SELECT m FROM MemberAcReplica m "
            + "WHERE m.memberId = :memberId AND m.problemId IN :problemIds AND m.accepted = true "
            + "ORDER BY m.problemId ASC, m.submittedAt ASC"
    )
    List<MemberAcReplica> findAllAcceptedByMemberIdAndProblemIdsWithLock(
        @Param("memberId") Long memberId,
        @Param("problemIds") List<Long> problemIds
    );
}
