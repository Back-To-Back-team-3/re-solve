package com.backtoback.study.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.recommendation.domain.StudyRecommendation;
import com.backtoback.study.recommendation.repository.StudyRecommendationRepository;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * 회원별 추천 결과 재계산의 대상 조건·교체·정렬·건수 제한을 실제 DB로 검증한다
 * (정책 및 상태 8.2 스터디 추천, ERD 4.5.11, 스케줄러 명세 5.4).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StudyRecommendationRefreshServiceTest {

    private static final long MEMBER_ID = 7L;
    private static final long OTHER_MEMBER_ID = 8L;
    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 11, 21, 9, 0);
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    private StudyRecommendationRefreshService refreshService;

    @Autowired
    private StudyRecommendationRepository recommendationRepository;

    @Autowired
    private MemberLearningProfileReplicaRepository learningProfileReplicaRepository;

    @Autowired
    private MemberReplicaRepository memberReplicaRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM study_recommendations");
        jdbc.update("DELETE FROM study_memberships");
        jdbc.update("DELETE FROM studies");
        jdbc.update("DELETE FROM member_learning_profile_replicas");
        jdbc.update("DELETE FROM member_replicas");
    }

    @Test
    @DisplayName("모집 중·정원 남음·신청하거나 가입하지 않은 스터디만 점수·근거·계산 시각과 함께 저장한다")
    void refresh_savesOnlyRecommendationTargets() {
        // given
        saveFullProfile(MEMBER_ID);
        long target = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        long left = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        insertMembership(left, MEMBER_ID, "LEFT");
        long pending = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        insertMembership(pending, MEMBER_ID, "PENDING");
        long approved = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        insertMembership(approved, MEMBER_ID, "APPROVED");
        insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "ACTIVE", 10, 2, null);
        insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 3, 3, null);

        // when
        refreshService.refresh(MEMBER_ID);

        // then
        List<StudyRecommendation> saved = recommendationRepository.findAllByMemberId(MEMBER_ID);
        assertThat(saved).extracting(StudyRecommendation::getStudyId).containsExactlyInAnyOrder(target, left);
        assertThat(saved).allSatisfy(recommendation -> {
            assertThat(recommendation.getScore()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(reasons(recommendation)).containsExactly("목표 일치", "태그 레벨 비슷함", "선호 언어 같음", "활동 시간대 겹침");
            assertThat(recommendation.getGeneratedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("다시 계산하면 이전 결과를 지우고 새 결과로 교체하며 다른 회원의 결과는 그대로 둔다")
    void refresh_replacesPreviousRecommendations() {
        // given
        saveFullProfile(MEMBER_ID);
        saveFullProfile(OTHER_MEMBER_ID);
        long first = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        refreshService.refresh(MEMBER_ID);
        refreshService.refresh(OTHER_MEMBER_ID);
        insertMembership(first, MEMBER_ID, "APPROVED");
        long second = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);

        // when
        refreshService.refresh(MEMBER_ID);

        // then
        assertThat(recommendationRepository.findAllByMemberId(MEMBER_ID))
            .extracting(StudyRecommendation::getStudyId)
            .containsExactly(second);
        assertThat(recommendationRepository.findAllByMemberId(OTHER_MEMBER_ID))
            .extracting(StudyRecommendation::getStudyId)
            .containsExactly(first);
    }

    @Test
    @DisplayName("0점인 스터디는 저장하지 않는다")
    void refresh_excludesZeroScore() {
        // given
        learningProfileReplicaRepository
            .save(
                MemberLearningProfileReplica.sync(MEMBER_ID, "STUDY", null, "[\"cpp\"]", "[\"MORNING\"]", 1L, SYNCED_AT)
            );
        long matched = insertStudy("STUDY", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);

        // when
        refreshService.refresh(MEMBER_ID);

        // then
        List<StudyRecommendation> saved = recommendationRepository.findAllByMemberId(MEMBER_ID);
        assertThat(saved).extracting(StudyRecommendation::getStudyId).containsExactly(matched);
        assertThat(saved.get(0).getScore()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(reasons(saved.get(0))).containsExactly("목표 일치");
    }

    @Test
    @DisplayName("점수가 같으면 최근 활동 시각이 늦은 순, 활동이 없으면 뒤에 두고 스터디 ID가 큰 순으로 상위 50건만 저장한다")
    void refresh_keepsTopRecommendationsInOrder() {
        // given — 100점 스터디 50개(활동 시각 1개·나머지 없음)와 활동이 가장 오래된 100점 스터디 1개, 90점 스터디 1개
        saveFullProfile(MEMBER_ID);
        long lowerScore = insertStudy("EMPLOYMENT", "LV2", "java", "MORNING", "RECRUITING", 10, 2, SYNCED_AT);
        long oldActivity
            = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, SYNCED_AT.minusDays(5));
        long recentActivity = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, SYNCED_AT);
        for (int i = 0; i < StudyRecommendationRefreshService.MAX_RECOMMENDATIONS - 1; i++) {
            insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        }

        // when
        refreshService.refresh(MEMBER_ID);

        // then — 활동 있는 100점 2개가 앞, 활동 없는 100점 49개 중 ID 큰 48개가 뒤에 오고 90점은 밀려난다
        List<StudyRecommendation> saved = recommendationRepository.findAllByMemberId(MEMBER_ID);
        assertThat(saved).hasSize(StudyRecommendationRefreshService.MAX_RECOMMENDATIONS);
        assertThat(saved).extracting(StudyRecommendation::getStudyId).doesNotContain(lowerScore);
        List<Long> savedIds = saved.stream().map(StudyRecommendation::getStudyId).toList();
        assertThat(savedIds).contains(recentActivity, oldActivity);
        long smallestNoActivityId
            = savedIds
                .stream()
                .filter(id -> id != recentActivity && id != oldActivity)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        assertThat(smallestNoActivityId).isEqualTo(recentActivity + 2);
    }

    @Test
    @DisplayName("태그 레벨만 있고 학습 프로필 입력이 없으면 기존 결과만 삭제하고 새로 저장하지 않는다")
    void refresh_deletesOnlyWithoutProfileInput() {
        // given
        long study = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        saveExistingRecommendation(MEMBER_ID, study);
        learningProfileReplicaRepository
            .save(MemberLearningProfileReplica.sync(MEMBER_ID, null, "{\"3\": \"LV2\"}", null, null, 1L, SYNCED_AT));

        // when
        refreshService.refresh(MEMBER_ID);

        // then
        assertThat(recommendationRepository.findAllByMemberId(MEMBER_ID)).isEmpty();
    }

    @Test
    @DisplayName("탈퇴한 회원은 학습 프로필 복제본이 남아 있어도 기존 결과만 삭제한다")
    void refresh_deletesOnlyForWithdrawnMember() {
        // given
        long study = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        saveExistingRecommendation(MEMBER_ID, study);
        saveFullProfile(MEMBER_ID);
        transactionTemplate
            .executeWithoutResult(status -> memberReplicaRepository.upsertWithdrawn(MEMBER_ID, 100L, SYNCED_AT));

        // when
        refreshService.refresh(MEMBER_ID);

        // then
        assertThat(recommendationRepository.findAllByMemberId(MEMBER_ID)).isEmpty();
    }

    @Test
    @DisplayName("학습 프로필 복제본이 없으면 기존 결과만 삭제한다")
    void refresh_deletesOnlyWithoutProfileReplica() {
        // given
        long study = insertStudy("EMPLOYMENT", "LV2", "java", "EVENING", "RECRUITING", 10, 2, null);
        saveExistingRecommendation(MEMBER_ID, study);

        // when
        refreshService.refresh(MEMBER_ID);

        // then
        assertThat(recommendationRepository.findAllByMemberId(MEMBER_ID)).isEmpty();
    }

    // MySQL JSON 컬럼은 저장 시 공백을 넣어 다시 직렬화하므로 원문 대신 값으로 비교한다.
    private static List<String> reasons(StudyRecommendation recommendation) {
        return JSON
            .readValue(
                recommendation.getReasonsJson(),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class)
            );
    }

    private void saveFullProfile(long memberId) {
        learningProfileReplicaRepository
            .save(
                MemberLearningProfileReplica
                    .sync(memberId, "EMPLOYMENT", "{\"3\": \"LV2\"}", "[\"java\"]", "[\"EVENING\"]", 1L, SYNCED_AT)
            );
    }

    private void saveExistingRecommendation(long memberId, long studyId) {
        recommendationRepository
            .save(StudyRecommendation.create(memberId, studyId, new BigDecimal("50.00"), "[\"목표 일치\"]", SYNCED_AT));
    }

    private long insertStudy(
        String goal,
        String skillLevel,
        String language,
        String timeSlot,
        String status,
        int capacity,
        int memberCount,
        LocalDateTime lastActivityAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO studies (name, goal, skill_level, preferred_language, active_time_slot,
                    capacity, member_count, manager_count, join_type, is_auto_reopen, status, version,
                    last_activity_at, created_at, updated_at)
                VALUES ('추천 대상', ?, ?, ?, ?, ?, ?, 0, 'APPROVAL', true, ?, 0, ?, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, goal);
            ps.setString(2, skillLevel);
            ps.setString(3, language);
            ps.setString(4, timeSlot);
            ps.setInt(5, capacity);
            ps.setInt(6, memberCount);
            ps.setString(7, status);
            ps.setTimestamp(8, lastActivityAt == null ? null : Timestamp.valueOf(lastActivityAt));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private void insertMembership(long studyId, long memberId, String membershipStatus) {
        jdbc
            .update(
                "INSERT INTO study_memberships (study_id, member_id, role, status, applied_at, created_at, updated_at) "
                    + "VALUES (?, ?, 'MEMBER', ?, NOW(6), NOW(6), NOW(6))",
                studyId,
                memberId,
                membershipStatus
            );
    }
}
