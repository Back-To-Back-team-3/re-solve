package com.backtoback.study.recommendation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.recommendation.domain.StudyRecommendation;

public interface StudyRecommendationRepository extends JpaRepository<StudyRecommendation, Long> {

    List<StudyRecommendation> findAllByMemberId(Long memberId);
}
