package com.backtoback.member.skill.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.member.skill.domain.MemberLevelHistory;

public interface MemberLevelHistoryRepository extends JpaRepository<MemberLevelHistory, Long> {

}
