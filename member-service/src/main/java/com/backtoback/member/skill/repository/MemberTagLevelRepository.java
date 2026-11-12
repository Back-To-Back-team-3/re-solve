package com.backtoback.member.skill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.member.skill.domain.MemberTagLevel;

public interface MemberTagLevelRepository extends JpaRepository<MemberTagLevel, Long> {

    boolean existsByMemberIdAndTagId(Long memberId, Long tagId);

    Optional<MemberTagLevel> findByMemberIdAndTagId(Long memberId, Long tagId);

    List<MemberTagLevel> findAllByMemberIdOrderByTagIdAsc(Long memberId);
}
