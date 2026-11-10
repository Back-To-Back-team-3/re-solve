package com.backtoback.study.hint.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.hint.domain.HintBlockReplica;

public interface HintBlockReplicaRepository extends JpaRepository<HintBlockReplica, Long> {

    // 이벤트 소비 메서드(INSERT IGNORE·DELETE·버전 비교)는 이벤트 소비자 구현 Issue에서 추가한다.
}
