package com.backtoback.study.replica.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.replica.domain.MemberAcReplica;

public interface MemberAcReplicaRepository extends JpaRepository<MemberAcReplica, Long> {

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
}
