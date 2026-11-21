package com.backtoback.study.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 저장된 추천 결과 조회의 대상 조건 재적용·정렬·페이지를 실제 MySQL로 검증한다
 * (API 명세서 5.1.4, 정책 및 상태 §8.2 스터디 추천 > 추천 대상·1차 방식).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StudyRecommendationQueryRepositoryTest {

    private static final long MEMBER_ID = 7L;
    private static final long OTHER_MEMBER_ID = 99L;
    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 11, 21, 3, 0);
    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 11, 10, 12, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 11, 20, 12, 0);

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
    private StudyRecommendationQueryRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM study_recommendations");
        jdbc.update("DELETE FROM study_memberships");
        jdbc.update("DELETE FROM studies");
    }

    @Test
    @DisplayName("모집 중이 아니거나 정원이 찼거나 신청·가입한 스터디의 저장 결과는 제외한다")
    void findPersonalizedStudies_reappliesTargetConditions() {
        // given
        long target = insertStudy("추천 대상", "RECRUITING", 10, 2, LATER);
        long left = insertStudy("탈퇴한 스터디", "RECRUITING", 10, 2, LATER);
        insertMembership(left, MEMBER_ID, "LEFT");
        long rejected = insertStudy("거절된 스터디", "RECRUITING", 10, 2, LATER);
        insertMembership(rejected, MEMBER_ID, "REJECTED");
        long active = insertStudy("진행 중", "ACTIVE", 10, 2, LATER);
        long full = insertStudy("정원 가득", "RECRUITING", 2, 2, LATER);
        long pending = insertStudy("신청 중", "RECRUITING", 10, 2, LATER);
        insertMembership(pending, MEMBER_ID, "PENDING");
        long approved = insertStudy("가입함", "RECRUITING", 10, 2, LATER);
        insertMembership(approved, MEMBER_ID, "APPROVED");
        for (long studyId : new long[] {
            target,
            left,
            rejected,
            active,
            full,
            pending,
            approved
        }) {
            insertRecommendation(MEMBER_ID, studyId, "70.00");
        }

        // when
        Page<PersonalizedStudyRow> result = repository.findPersonalizedStudies(MEMBER_ID, PageRequest.of(0, 20));

        // then
        assertThat(result.getContent())
            .extracting(PersonalizedStudyRow::getStudyId)
            .containsExactlyInAnyOrder(target, left, rejected);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("점수가 높은 순, 같으면 최근 활동이 늦은 순, 활동 전 스터디는 뒤, 그다음 최신 스터디 순으로 반환한다")
    void findPersonalizedStudies_sortsByScoreThenActivityThenId() {
        // given
        long lowScore = insertStudy("낮은 점수", "RECRUITING", 10, 2, LATER);
        long noActivityOlder = insertStudy("활동 전 이전", "RECRUITING", 10, 2, null);
        long noActivityNewer = insertStudy("활동 전 최신", "RECRUITING", 10, 2, null);
        long earlierActivity = insertStudy("이전 활동", "RECRUITING", 10, 2, EARLIER);
        long laterActivity = insertStudy("최근 활동", "RECRUITING", 10, 2, LATER);
        insertRecommendation(MEMBER_ID, lowScore, "30.00");
        insertRecommendation(MEMBER_ID, noActivityOlder, "70.00");
        insertRecommendation(MEMBER_ID, noActivityNewer, "70.00");
        insertRecommendation(MEMBER_ID, earlierActivity, "70.00");
        insertRecommendation(MEMBER_ID, laterActivity, "70.00");

        // when
        Page<PersonalizedStudyRow> result = repository.findPersonalizedStudies(MEMBER_ID, PageRequest.of(0, 20));

        // then
        assertThat(result.getContent())
            .extracting(PersonalizedStudyRow::getStudyId)
            .containsExactly(laterActivity, earlierActivity, noActivityNewer, noActivityOlder, lowScore);
    }

    @Test
    @DisplayName("다른 회원의 추천 결과는 포함하지 않고 페이지 크기와 전체 건수를 함께 반환한다")
    void findPersonalizedStudies_pagesOnlyOwnRows() {
        // given
        long first = insertStudy("첫째", "RECRUITING", 10, 2, LATER);
        long second = insertStudy("둘째", "RECRUITING", 10, 2, LATER);
        long third = insertStudy("셋째", "RECRUITING", 10, 2, LATER);
        insertRecommendation(MEMBER_ID, first, "90.00");
        insertRecommendation(MEMBER_ID, second, "80.00");
        insertRecommendation(MEMBER_ID, third, "70.00");
        insertRecommendation(OTHER_MEMBER_ID, first, "100.00");

        // when
        Page<PersonalizedStudyRow> result = repository.findPersonalizedStudies(MEMBER_ID, PageRequest.of(1, 2));

        // then
        assertThat(result.getContent()).extracting(PersonalizedStudyRow::getStudyId).containsExactly(third);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("점수와 추천 근거 JSON 원문, 스터디 정보를 그대로 읽는다")
    void findPersonalizedStudies_readsScoreAndReasonsJson() {
        // given
        long studyId = insertStudy("코딩테스트 스터디", "RECRUITING", 10, 5, LATER);
        insertRecommendation(MEMBER_ID, studyId, "87.00");

        // when
        PersonalizedStudyRow row
            = repository.findPersonalizedStudies(MEMBER_ID, PageRequest.of(0, 20)).getContent().get(0);

        // then
        assertThat(row.getName()).isEqualTo("코딩테스트 스터디");
        assertThat(row.getScore()).isEqualByComparingTo(new BigDecimal("87.00"));
        assertThat(row.getReasonsJson()).contains("목표 일치").contains("선호 언어 같음");
        assertThat(row.getMemberCount()).isEqualTo(5);
        assertThat(row.getCapacity()).isEqualTo(10);
        assertThat(row.getStatus()).isEqualTo("RECRUITING");
    }

    private long insertStudy(String name, String status, int capacity, int memberCount, LocalDateTime lastActivityAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO studies (name, goal, skill_level, preferred_language, active_time_slot,
                    capacity, member_count, manager_count, join_type, is_auto_reopen, status, version,
                    last_activity_at, created_at, updated_at)
                VALUES (?, 'EMPLOYMENT', 'LV2', 'java', 'EVENING', ?, ?, 0, 'APPROVAL', true, ?, 0, ?, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, capacity);
            ps.setInt(3, memberCount);
            ps.setString(4, status);
            ps.setTimestamp(5, lastActivityAt == null ? null : Timestamp.valueOf(lastActivityAt));
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

    private void insertRecommendation(long memberId, long studyId, String score) {
        jdbc
            .update(
                "INSERT INTO study_recommendations (member_id, study_id, score, reasons_json, generated_at, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                memberId,
                studyId,
                new BigDecimal(score),
                "[\"목표 일치\", \"선호 언어 같음\"]",
                Timestamp.valueOf(GENERATED_AT)
            );
    }
}
