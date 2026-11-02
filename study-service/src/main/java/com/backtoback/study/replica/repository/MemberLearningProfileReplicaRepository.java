package com.backtoback.study.replica.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

public interface MemberLearningProfileReplicaRepository extends JpaRepository<MemberLearningProfileReplica, Long> {

    List<MemberLearningProfileReplica> findAllByMemberIdIn(Collection<Long> memberIds);
}
