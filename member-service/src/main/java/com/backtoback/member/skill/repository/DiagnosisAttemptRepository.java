package com.backtoback.member.skill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisStatus;

import jakarta.persistence.LockModeType;

public interface DiagnosisAttemptRepository extends JpaRepository<DiagnosisAttempt, Long> {

    Optional<DiagnosisAttempt> findByIdAndMemberId(Long id, Long memberId);

    /**
     * 코드 저장·제출용 조회. 같은 응시의 저장이 겹치지 않고, 종료 처리와 순서가 정해지도록 행을 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DiagnosisAttempt a where a.id = :id and a.memberId = :memberId")
    Optional<DiagnosisAttempt> findForUpdateByIdAndMemberId(Long id, Long memberId);

    boolean existsByMemberIdAndTagIdAndStatus(Long memberId, Long tagId, DiagnosisStatus status);

    List<DiagnosisAttempt> findAllByMemberIdAndStatus(Long memberId, DiagnosisStatus status);

    // tagId가 null이면 전체 태그
    @Query("select a from DiagnosisAttempt a where a.memberId = :memberId and (:tagId is null or a.tagId = :tagId)")
    Page<DiagnosisAttempt> search(Long memberId, Long tagId, Pageable pageable);
}
