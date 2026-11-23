package com.backtoback.study.recommendation.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 추천 스터디 조회 API의 대상 필터·정렬·활동 집계와 경로 매핑을 실제 DB로 검증한다(API 명세서 5.1.4).
 *
 * @WebMvcTest는 JpaAuditingConfiguration으로 인한 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 §6.7)
 * @SpringBootTest + Testcontainers를 사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StudyRecommendationControllerTest {

    private static final long MEMBER_ID = 7L;
    private static final long OTHER_MEMBER_ID = 99L;
    private static final String USER_ROLE = "USER";

    // 활동 기간 경계와 충분히 떨어진 시각을 써서 실행 시각·시간대 차이에 영향받지 않게 한다.
    private static final LocalDateTime NOW = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime RECENT = NOW.minusDays(1);
    private static final LocalDateTime OLD = NOW.minusDays(10);

    @Container
    @SuppressWarnings("rawtypes")
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM study_recommendations");
        jdbc.update("DELETE FROM member_learning_profile_replicas");
        jdbc.update("DELETE FROM assignment_completions");
        jdbc.update("DELETE FROM assignments");
        jdbc.update("DELETE FROM study_memberships");
        jdbc.update("DELETE FROM studies");
    }

    @Test
    @DisplayName("모집 중·정원 남음·미소속 스터디만 최근 활동 수, 인원, 최신 생성 순으로 반환한다")
    void getRecommendations_filtersAndSorts() throws Exception {
        // given
        long active = insertStudy("활동 많음", "RECRUITING", 10, 2, OLD);
        long assignment = insertAssignment(active);
        insertCompletion(assignment, 1L, "ON_TIME", RECENT);
        insertCompletion(assignment, 2L, "LATE", RECENT);
        long manyMembers = insertStudy("인원 많음", "RECRUITING", 10, 5, OLD);
        long newer = insertStudy("최신 생성", "RECRUITING", 10, 2, NOW.minusDays(2));
        long older = insertStudy("이전 생성", "RECRUITING", 10, 2, NOW.minusDays(3));
        long left = insertStudy("탈퇴한 스터디", "RECRUITING", 10, 1, OLD);
        insertMembership(left, MEMBER_ID, "LEFT");
        insertStudy("진행 중", "ACTIVE", 10, 2, OLD);
        insertStudy("정원 가득", "RECRUITING", 2, 2, OLD);
        insertMembership(insertStudy("신청 중", "RECRUITING", 10, 2, OLD), MEMBER_ID, "PENDING");
        insertMembership(insertStudy("가입함", "RECRUITING", 10, 2, OLD), MEMBER_ID, "APPROVED");
        long otherJoined = insertStudy("다른 회원 가입", "RECRUITING", 10, 2, NOW.minusDays(4));
        insertMembership(otherJoined, OTHER_MEMBER_ID, "APPROVED");

        // when & then
        mockMvc
            .perform(
                get("/api/v1/studies/recommendations")
                    .header("X-User-Id", String.valueOf(MEMBER_ID))
                    .header("X-User-Role", USER_ROLE)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.mode").value("POPULAR"))
            .andExpect(jsonPath("$.data.isProfileRequired").value(true))
            .andExpect(jsonPath("$.data.totalElements").value(6))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(
                jsonPath("$.data.content[*].studyId")
                    .value(
                        contains(
                            String.valueOf(active),
                            String.valueOf(manyMembers),
                            String.valueOf(newer),
                            String.valueOf(older),
                            String.valueOf(otherJoined),
                            String.valueOf(left)
                        )
                    )
            )
            .andExpect(jsonPath("$.data.content[0]").value(hasKey("score")))
            .andExpect(jsonPath("$.data.content[0].score").value(nullValue()))
            .andExpect(jsonPath("$.data.content[0].reasons[0]").value("최근 활동이 많은 스터디"))
            .andExpect(jsonPath("$.data.content[0].memberCount").value(2))
            .andExpect(jsonPath("$.data.content[0].capacity").value(10))
            .andExpect(jsonPath("$.data.content[0].status").value("RECRUITING"));
    }

    @Test
    @DisplayName("기존 풀이 인정·인정 취소·7일 이전 완료는 최근 활동 수에 포함하지 않는다")
    void getRecommendations_excludesNonRecentActivity() throws Exception {
        // given
        long excludedOnly = insertStudy("제외 기록만", "RECRUITING", 10, 5, OLD);
        long excludedAssignment = insertAssignment(excludedOnly);
        insertCompletion(excludedAssignment, 1L, "PRE_SOLVED", RECENT);
        insertCompletion(excludedAssignment, 2L, "PRE_SOLVED", RECENT);
        insertRevokedCompletion(excludedAssignment, 3L);
        insertCompletion(excludedAssignment, 4L, "ON_TIME", OLD);
        long recentOne = insertStudy("최근 완료 1건", "RECRUITING", 10, 2, OLD);
        insertCompletion(insertAssignment(recentOne), 1L, "ON_TIME", RECENT);

        // when & then
        mockMvc
            .perform(
                get("/api/v1/studies/recommendations")
                    .header("X-User-Id", String.valueOf(MEMBER_ID))
                    .header("X-User-Role", USER_ROLE)
            )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.data.content[*].studyId")
                    .value(contains(String.valueOf(recentOne), String.valueOf(excludedOnly)))
            );
    }

    @Test
    @DisplayName("학습 프로필 입력과 저장된 추천 결과가 있으면 PERSONALIZED 모드로 점수와 추천 근거를 반환한다")
    void getRecommendations_storedResult_returnsPersonalized() throws Exception {
        // given
        long studyId = insertStudy("개인화 추천", "RECRUITING", 10, 5, OLD);
        insertLearningProfile(MEMBER_ID);
        jdbc
            .update(
                "INSERT INTO study_recommendations (member_id, study_id, score, reasons_json, generated_at, "
                    + "created_at, updated_at) VALUES (?, ?, 87.00, ?, ?, NOW(6), NOW(6))",
                MEMBER_ID,
                studyId,
                "[\"목표 일치\", \"태그 레벨 비슷함\", \"선호 언어 같음\"]",
                Timestamp.valueOf(NOW)
            );

        // when & then
        mockMvc
            .perform(
                get("/api/v1/studies/recommendations")
                    .header("X-User-Id", String.valueOf(MEMBER_ID))
                    .header("X-User-Role", USER_ROLE)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mode").value("PERSONALIZED"))
            .andExpect(jsonPath("$.data.isProfileRequired").value(false))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].studyId").value(String.valueOf(studyId)))
            .andExpect(jsonPath("$.data.content[0].score").value(87.00))
            .andExpect(jsonPath("$.data.content[0].reasons").value(contains("목표 일치", "태그 레벨 비슷함", "선호 언어 같음")))
            .andExpect(jsonPath("$.data.content[0].memberCount").value(5))
            .andExpect(jsonPath("$.data.content[0].status").value("RECRUITING"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401을 반환한다")
    void getRecommendations_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/studies/recommendations")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("숫자 ID 경로는 추천 조회가 아니라 스터디 상세 조회로 처리한다")
    void getStudyDetail_numericId_routesToDetail() throws Exception {
        long studyId = insertStudy("상세 조회 대상", "RECRUITING", 10, 2, OLD);

        mockMvc
            .perform(get("/api/v1/studies/{studyId}", studyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.studyId").value(String.valueOf(studyId)))
            .andExpect(jsonPath("$.data.name").value("상세 조회 대상"));
    }

    private long insertStudy(String name, String status, int capacity, int memberCount, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO studies (name, goal, skill_level, preferred_language, active_time_slot,
                    capacity, member_count, manager_count, join_type, is_auto_reopen, status, version,
                    created_at, updated_at)
                VALUES (?, 'EMPLOYMENT', 'LV2', 'java', 'EVENING', ?, ?, 0, 'APPROVAL', true, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, capacity);
            ps.setInt(3, memberCount);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.valueOf(createdAt));
            ps.setTimestamp(6, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private void insertLearningProfile(long memberId) {
        jdbc
            .update(
                "INSERT INTO member_learning_profile_replicas (member_id, goal, profile_version, synced_at, "
                    + "created_at, updated_at) VALUES (?, 'EMPLOYMENT', 1, NOW(6), NOW(6), NOW(6))",
                memberId
            );
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

    private long insertAssignment(long studyId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps
                = connection
                    .prepareStatement(
                        "INSERT INTO assignments (study_id, title, is_pre_solved_allowed, starts_at, status, "
                            + "created_at, updated_at) VALUES (?, '문제집', true, NOW(6), 'OPEN', NOW(6), NOW(6))",
                        Statement.RETURN_GENERATED_KEYS
                    );
            ps.setLong(1, studyId);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private void insertCompletion(long assignmentId, long memberId, String completionType, LocalDateTime completedAt) {
        jdbc
            .update(
                "INSERT INTO assignment_completions (assignment_id, member_id, problem_id, status, completion_type, "
                    + "completed_at, created_at, updated_at) VALUES (?, ?, 1, 'COMPLETED', ?, ?, NOW(6), NOW(6))",
                assignmentId,
                memberId,
                completionType,
                Timestamp.valueOf(completedAt)
            );
    }

    // 재채점으로 인정이 취소되면 완료 유형과 인정 시각이 비워진다(ERD assignment_completions).
    private void insertRevokedCompletion(long assignmentId, long memberId) {
        jdbc
            .update(
                "INSERT INTO assignment_completions (assignment_id, member_id, problem_id, status, "
                    + "created_at, updated_at) VALUES (?, ?, 1, 'REVOKED', NOW(6), NOW(6))",
                assignmentId,
                memberId
            );
    }
}
