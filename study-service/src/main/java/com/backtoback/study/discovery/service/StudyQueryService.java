package com.backtoback.study.discovery.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.discovery.repository.StudySpecification;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyRepository;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 탐색(목록·상세·내 스터디) 조회를 담당한다. 가입·운영처럼 상태를 바꾸는 처리는 다루지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StudyQueryService {

    private final StudyRepository studyRepository;

    /**
     * 모집 중인 스터디를 조건으로 검색한다(API 명세서 5.1.1). 학습 프로필이 없어도 호출할 수 있다.
     */
    @Transactional(readOnly = true)
    public Page<StudySummaryResponse> searchStudies(
        String keyword,
        String goal,
        String skillLevel,
        String preferredLanguage,
        String activeTimeSlot,
        Pageable pageable
    ) {
        Specification<Study> specification
            = Specification
                .where(StudySpecification.statusEquals(StudyStatus.RECRUITING))
                .and(StudySpecification.keywordContains(keyword))
                .and(StudySpecification.goalEquals(goal))
                .and(StudySpecification.skillLevelEquals(skillLevel))
                .and(StudySpecification.preferredLanguageEquals(preferredLanguage))
                .and(StudySpecification.activeTimeSlotEquals(activeTimeSlot));

        return studyRepository.findAll(specification, pageable).map(StudySummaryResponse::from);
    }
}
