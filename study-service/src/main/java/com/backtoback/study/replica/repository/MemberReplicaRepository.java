package com.backtoback.study.replica.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.replica.domain.MemberReplica;

public interface MemberReplicaRepository extends JpaRepository<MemberReplica, Long> {

    Optional<MemberReplica> findByMemberId(Long memberId);

    List<MemberReplica> findAllByMemberIdIn(Collection<Long> memberIds);
}
