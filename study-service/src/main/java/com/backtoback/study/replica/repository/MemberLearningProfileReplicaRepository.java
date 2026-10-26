package com.backtoback.study.replica.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

public interface MemberLearningProfileReplicaRepository extends JpaRepository<MemberLearningProfileReplica, Long> {

    // 조회 메서드는 필요한 기능 Issue에서 추가한다.
}
