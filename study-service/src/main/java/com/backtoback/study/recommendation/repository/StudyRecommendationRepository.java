package com.backtoback.study.recommendation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.recommendation.domain.StudyRecommendation;

public interface StudyRecommendationRepository extends JpaRepository<StudyRecommendation, Long> {

    List<StudyRecommendation> findAllByMemberId(Long memberId);

    /**
     * 회원의 추천 결과를 모두 물리 삭제한다(ERD 4.5.11 — 파생 데이터라 물리 삭제 허용).
     * 같은 트랜잭션에서 이어 저장하는 새 결과가 유일 키 {@code (member_id, study_id)}와 충돌하지 않도록 즉시 실행한다.
     */
    @Modifying(
        flushAutomatically = true,
        clearAutomatically = true
    )
    @Query("DELETE FROM StudyRecommendation r WHERE r.memberId = :memberId")
    void deleteAllByMemberId(@Param("memberId") Long memberId);
}
