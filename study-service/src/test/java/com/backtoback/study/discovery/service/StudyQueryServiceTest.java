package com.backtoback.study.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.repository.StudyRepository;

/**
 * 선택 필터가 모두 없거나 모두 있어도 예외 없이 조회되고, 조회 결과가 응답 DTO로 올바르게 바뀌는지 검증한다.
 * {@code Specification.and(null)}이 예외를 던지는 버전 차이 회귀를 잡기 위한 테스트다.
 */
@ExtendWith(MockitoExtension.class)
class StudyQueryServiceTest {

    @Mock
    private StudyRepository studyRepository;

    private StudyQueryService service;
    private Pageable pageable;

    @BeforeEach
    void setUp() {
        service = new StudyQueryService(studyRepository);
        pageable = PageRequest.of(0, 20);
    }

    @Test
    @DisplayName("선택 필터가 모두 없어도 예외 없이 RECRUITING 스터디를 조회하고 응답 DTO로 바꾼다")
    void searchesWithoutOptionalFilters() {
        // given
        Study study = recruitingStudy();
        given(studyRepository.findAll(any(Specification.class), eq(pageable)))
            .willReturn(new PageImpl<>(List.of(study), pageable, 1));

        // when
        Page<StudySummaryResponse> result = service.searchStudies(null, null, null, null, null, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        StudySummaryResponse response = result.getContent().getFirst();
        assertThat(response.studyId()).isEqualTo("1");
        assertThat(response.name()).isEqualTo("코딩테스트 스터디");
        assertThat(response.goal()).isEqualTo("EMPLOYMENT");
    }

    @Test
    @DisplayName("선택 필터를 모두 지정해도 예외 없이 조회한다")
    void searchesWithAllOptionalFilters() {
        // given
        Study study = recruitingStudy();
        given(studyRepository.findAll(any(Specification.class), eq(pageable)))
            .willReturn(new PageImpl<>(List.of(study), pageable, 1));

        // when
        Page<StudySummaryResponse> result
            = service.searchStudies("코딩테스트", "EMPLOYMENT", "LV2", "java", "EVENING", pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
    }

    private Study recruitingStudy() {
        Study study
            = Study
                .recruit(
                    "코딩테스트 스터디",
                    "취업 준비 알고리즘 스터디",
                    "EMPLOYMENT",
                    "LV2",
                    "java",
                    "EVENING",
                    10,
                    JoinType.APPROVAL,
                    true
                );
        ReflectionTestUtils.setField(study, "id", 1L);
        return study;
    }
}
