package com.backtoback.study.replica.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

public interface MemberLearningProfileReplicaRepository extends JpaRepository<MemberLearningProfileReplica, Long> {

    List<MemberLearningProfileReplica> findAllByMemberIdIn(Collection<Long> memberIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM MemberLearningProfileReplica m WHERE m.memberId = :memberId")
    void deleteByMemberId(@Param("memberId") Long memberId);
}
