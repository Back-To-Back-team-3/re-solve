package com.backtoback.member.skill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisStatus;

public interface DiagnosisAttemptRepository extends JpaRepository<DiagnosisAttempt, Long> {

    Optional<DiagnosisAttempt> findByIdAndMemberId(Long id, Long memberId);

    boolean existsByMemberIdAndTagIdAndStatus(Long memberId, Long tagId, DiagnosisStatus status);

    List<DiagnosisAttempt> findAllByMemberIdAndStatus(Long memberId, DiagnosisStatus status);

    // tagId가 null이면 전체 태그
    @Query("select a from DiagnosisAttempt a where a.memberId = :memberId and (:tagId is null or a.tagId = :tagId)")
    Page<DiagnosisAttempt> search(Long memberId, Long tagId, Pageable pageable);
}
