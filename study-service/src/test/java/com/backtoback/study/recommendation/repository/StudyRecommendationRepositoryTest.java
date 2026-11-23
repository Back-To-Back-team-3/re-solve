package com.backtoback.study.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.recommendation.domain.StudyRecommendation;

import jakarta.persistence.EntityManager;

/**
 * 추천 결과 테이블 매핑·제약 통합 테스트 (ERD 4.5.11 study_recommendations).
 * 마이그레이션과 엔티티가 맞는지, 회원·스터디 유일 제약이 동작하는지 실제 MySQL로 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class StudyRecommendationRepositoryTest {

    private static final Long MEMBER_ID = 7L;
    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 11, 21, 10, 0, 0, 123_456_000);

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StudyRecommendationRepository repository;

    @Autowired
    private StudyRepository studyRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("추천 결과를 저장하면 점수·근거·계산 시각을 그대로 다시 읽는다")
    void save_recommendation_readsBackSameValues() {
        // given
        Long studyId = saveStudy();
        StudyRecommendation recommendation
            = StudyRecommendation
                .create(MEMBER_ID, studyId, new BigDecimal("87.50"), "[\"목표 일치\", \"태그 레벨 비슷함\"]", GENERATED_AT);

        // when
        repository.saveAndFlush(recommendation);
        // 영속성 컨텍스트를 비워 DB에 저장된 값을 다시 읽는다.
        entityManager.clear();

        // then
        assertThat(repository.findAllByMemberId(MEMBER_ID)).hasSize(1);
        StudyRecommendation saved = repository.findAllByMemberId(MEMBER_ID).get(0);
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getStudyId()).isEqualTo(studyId);
        assertThat(saved.getScore()).isEqualByComparingTo("87.50");
        assertThat(saved.getReasonsJson()).contains("목표 일치");
        assertThat(saved.getGeneratedAt()).isEqualTo(GENERATED_AT);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 회원·스터디 추천 결과를 두 번 저장하면 유일 제약 위반으로 실패한다")
    void save_duplicateMemberStudy_violatesUniqueKey() {
        // given
        Long studyId = saveStudy();
        repository
            .saveAndFlush(StudyRecommendation.create(MEMBER_ID, studyId, new BigDecimal("50.00"), "[]", GENERATED_AT));

        // when & then
        assertThatThrownBy(
            () -> repository
                .saveAndFlush(
                    StudyRecommendation.create(MEMBER_ID, studyId, new BigDecimal("60.00"), "[]", GENERATED_AT)
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 스터디로 추천 결과를 저장하면 외래 키 위반으로 실패한다")
    void save_unknownStudy_violatesForeignKey() {
        // when & then
        assertThatThrownBy(
            () -> repository
                .saveAndFlush(
                    StudyRecommendation.create(MEMBER_ID, 999_999L, new BigDecimal("10.00"), "[]", GENERATED_AT)
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long saveStudy() {
        Study study
            = Study.recruit("추천 테스트 스터디", null, "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
        return studyRepository.saveAndFlush(study).getId();
    }
}
