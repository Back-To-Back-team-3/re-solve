package com.backtoback.study.assignment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyStatus;

/**
 * 배정 문제 판정 쿼리 통합 테스트 (정책 및 상태 §8.3 문제 접근 제한, API 명세서 5.4.5·5.7.4).
 * 다섯 테이블의 상태 조건을 한 쿼리로 판정하므로 실제 MySQL로 경계값을 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AssignmentProblemRepositoryTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long OTHER_MEMBER_ID = 8L;
    private static final Long PROBLEM_ID = 101L;
    private static final Long OTHER_PROBLEM_ID = 102L;
    private static final List<AssignmentStatus> ASSIGNED_STATUSES
        = List.of(AssignmentStatus.OPEN, AssignmentStatus.CLOSED);

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
    private AssignmentProblemRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("승인된 대상자에게 OPEN 문제집의 ACTIVE 문제를 조회하면 배정 문제로 판정한다")
    void existsAssignedProblem_openAssignment_returnsTrue() {
        // given
        Long studyId = insertStudy("ACTIVE");
        Long membershipId = insertMembership(studyId, MEMBER_ID, "APPROVED");
        Long assignmentId = insertAssignment(studyId, "OPEN");
        insertProblem(assignmentId, PROBLEM_ID, "ACTIVE");
        insertTarget(assignmentId, MEMBER_ID, membershipId);

        // when & then
        assertThat(isAssigned(MEMBER_ID, PROBLEM_ID)).isTrue();
    }

    @Test
    @DisplayName("마감된 문제집의 ACTIVE 문제를 조회하면 배정 문제로 판정한다")
    void existsAssignedProblem_closedAssignment_returnsTrue() {
        // given
        Long studyId = insertStudy("ACTIVE");
        Long membershipId = insertMembership(studyId, MEMBER_ID, "APPROVED");
        Long assignmentId = insertAssignment(studyId, "CLOSED");
        insertProblem(assignmentId, PROBLEM_ID, "ACTIVE");
        insertTarget(assignmentId, MEMBER_ID, membershipId);

        // when & then
        assertThat(isAssigned(MEMBER_ID, PROBLEM_ID)).isTrue();
    }

    @Test
    @DisplayName("공개 전 SCHEDULED 문제집의 문제를 조회하면 배정 문제가 아니다")
    void existsAssignedProblem_scheduledAssignment_returnsFalse() {
        // given
        Long studyId = insertStudy("ACTIVE");
        Long membershipId = insertMembership(studyId, MEMBER_ID, "APPROVED");
        Long assignmentId = insertAssignment(studyId, "SCHEDULED");
        insertProblem(assignmentId, PROBLEM_ID, "ACTIVE");
        insertTarget(assignmentId, MEMBER_ID, membershipId);

        // when & then
        assertThat(isAssigned(MEMBER_ID, PROBLEM_ID)).isFalse();
    }

    @Test
    @DisplayName("문제집에서 제외된 EXCLUDED 문제를 조회하면 배정 문제가 아니다")
    void existsAssignedProblem_excludedProblem_returnsFalse() {
        // given
        Long studyId = insertStudy("ACTIVE");
        Long membershipId = insertMembership(studyId, MEMBER_ID, "APPROVED");
        Long assignmentId = insertAssignment(studyId, "OPEN");
        insertProblem(assignmentId, PROBLEM_ID, "EXCLUDED");
        insertTarget(assignmentId, MEMBER_ID, membershipId);

        // when & then
        assertThat(isAssigned(MEMBER_ID, PROBLEM_ID)).isFalse();
    }

    @Test
    @DisplayName("탈퇴·강제 탈퇴·승인 대기 회원이 조회하면 배정 문제가 아니다")
    void existsAssignedProblem_notApprovedMembership_returnsFalse() {
        for (String membershipStatus : List.of("LEFT", "REMOVED", "PENDING")) {
            // given
            Long studyId = insertStudy("ACTIVE");
            Long membershipId = insertMembership(studyId, MEMBER_ID, membershipStatus);
            Long assignmentId = insertAssignment(studyId, "OPEN");
            Long problemId = PROBLEM_ID + studyId;
            insertProblem(assignmentId, problemId, "ACTIVE");
            insertTarget(assignmentId, MEMBER_ID, membershipId);

            // when & then
            assertThat(isAssigned(MEMBER_ID, problemId)).as(membershipStatus).isFalse();
        }
    }

    @Test
    @DisplayName("탈퇴 후 재가입해 승인된 회원이 이전 대상자 행으로 조회하면 배정 문제로 판정한다")
    void existsAssignedProblem_rejoinedMember_returnsTrue() {
        // given
        Long studyId = insertStudy("ACTIVE");
        Long leftMembershipId = insertMembership(studyId, MEMBER_ID, "LEFT");
        Long assignmentId = insertAssignment(studyId, "CLOSED");
        insertProblem(assignmentId, PROBLEM_ID, "ACTIVE");
        // 재가입해도 대상자 행에는 처음 편입된 가입 ID가 남는다.
        insertTarget(assignmentId, MEMBER_ID, leftMembershipId);
        insertMembership(studyId, MEMBER_ID, "APPROVED");

        // when & then
        assertThat(isAssigned(MEMBER_ID, PROBLEM_ID)).isTrue();
    }

    @Test
    @DisplayName("종료된 CLOSED 스터디의 문제를 조회하면 배정 문제가 아니다")
    void existsAssignedProblem_closedStudy_returnsFalse() {
        // given
        Long studyId = insertStudy("CLOSED");
        Long membershipId = insertMembership(studyId, MEMBER_ID, "APPROVED");
        Long assignmentId = insertAssignment(studyId, "CLOSED");
        insertProblem(assignmentId, PROBLEM_ID, "ACTIVE");
        insertTarget(assignmentId, MEMBER_ID, membershipId);

        // when & then
        assertThat(isAssigned(MEMBER_ID, PROBLEM_ID)).isFalse();
    }

    @Test
    @DisplayName("모집 중인 RECRUITING 스터디의 문제를 조회하면 배정 문제로 판정한다")
    void existsAssignedProblem_recruitingStudy_returnsTrue() {
        // given
        Long studyId = insertStudy("RECRUITING");
        Long membershipId = insertMembership(studyId, MEMBER_ID, "APPROVED");
        Long assignmentId = insertAssignment(studyId, "OPEN");
        insertProblem(assignmentId, PROBLEM_ID, "ACTIVE");
        insertTarget(assignmentId, MEMBER_ID, membershipId);

        // when & then
        assertThat(isAssigned(MEMBER_ID, PROBLEM_ID)).isTrue();
    }

    @Test
    @DisplayName("승인된 구성원이라도 문제집 대상자가 아니면 배정 문제가 아니다")
    void existsAssignedProblem_notTarget_returnsFalse() {
        // given
        Long studyId = insertStudy("ACTIVE");
        Long membershipId = insertMembership(studyId, MEMBER_ID, "APPROVED");
        insertMembership(studyId, OTHER_MEMBER_ID, "APPROVED");
        Long assignmentId = insertAssignment(studyId, "OPEN");
        insertProblem(assignmentId, PROBLEM_ID, "ACTIVE");
        insertTarget(assignmentId, MEMBER_ID, membershipId);

        // when & then
        assertThat(isAssigned(OTHER_MEMBER_ID, PROBLEM_ID)).isFalse();
    }

    @Test
    @DisplayName("배정되지 않은 문제나 없는 문제를 조회하면 배정 문제가 아니다")
    void existsAssignedProblem_unassignedProblem_returnsFalse() {
        // given
        Long studyId = insertStudy("ACTIVE");
        Long membershipId = insertMembership(studyId, MEMBER_ID, "APPROVED");
        Long assignmentId = insertAssignment(studyId, "OPEN");
        insertProblem(assignmentId, PROBLEM_ID, "ACTIVE");
        insertTarget(assignmentId, MEMBER_ID, membershipId);

        // when & then
        assertThat(isAssigned(MEMBER_ID, OTHER_PROBLEM_ID)).isFalse();
    }

    private boolean isAssigned(Long memberId, Long problemId) {
        return repository
            .existsAssignedProblem(
                memberId,
                problemId,
                AssignmentProblemStatus.ACTIVE,
                ASSIGNED_STATUSES,
                MembershipStatus.APPROVED,
                StudyStatus.CLOSED
            );
    }

    private Long insertStudy(String status) {
        return insert(
            "INSERT INTO studies (name, goal, skill_level, preferred_language, active_time_slot, capacity, "
                + "member_count, manager_count, join_type, is_auto_reopen, status, version, created_at, updated_at) "
                + "VALUES ('스터디', 'JOB', 'LV1', 'JAVA', 'EVENING', 10, 1, 0, 'INSTANT', false, ?, 0, NOW(6), NOW(6))",
            status
        );
    }

    private Long insertMembership(Long studyId, Long memberId, String status) {
        return insert(
            "INSERT INTO study_memberships (study_id, member_id, role, status, applied_at, created_at, updated_at) "
                + "VALUES (?, ?, 'MEMBER', ?, NOW(6), NOW(6), NOW(6))",
            studyId,
            memberId,
            status
        );
    }

    private Long insertAssignment(Long studyId, String status) {
        return insert(
            "INSERT INTO assignments (study_id, title, is_pre_solved_allowed, starts_at, status, created_at, "
                + "updated_at) VALUES (?, '문제집', false, NOW(6), ?, NOW(6), NOW(6))",
            studyId,
            status
        );
    }

    private void insertProblem(Long assignmentId, Long problemId, String status) {
        insert(
            "INSERT INTO assignment_problems (assignment_id, problem_id, assigned_revision_id, problem_title, "
                + "problem_difficulty, problem_tags_json, display_order, status, created_at, updated_at) "
                + "VALUES (?, ?, 1, '문제', 3, JSON_ARRAY(), 1, ?, NOW(6), NOW(6))",
            assignmentId,
            problemId,
            status
        );
    }

    private void insertTarget(Long assignmentId, Long memberId, Long membershipId) {
        insert(
            "INSERT INTO assignment_member_statuses (assignment_id, member_id, membership_id, status, completed_count, "
                + "on_time_count, created_at, updated_at) VALUES (?, ?, ?, 'NOT_STARTED', 0, 0, NOW(6), NOW(6))",
            assignmentId,
            memberId,
            membershipId
        );
    }

    private Long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement
                = connection
                    .prepareStatement(
                        sql,
                        new String[] {
                            "id"
            }
                    );
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
