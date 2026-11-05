package com.backtoback.member.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.admin.domain.AuditLog;
import com.backtoback.member.admin.repository.AuditLogRepository;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 검색 쿼리(LIKE 이스케이프·조건·정렬)와 감사 로그 JSON 컬럼은 MySQL에 의존하므로 실제 MySQL로 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AdminMemberServiceTest {

    private static final PageRequest NEWEST_FIRST
        = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("member_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("resolve.auth.github.client-id", () -> "client-id");
        registry.add("resolve.auth.github.client-secret", () -> "client-secret");
        registry.add("resolve.auth.jwt.secret", () -> "test-jwt-secret-key-must-be-at-least-32-bytes");
    }

    @Autowired
    private AdminMemberService adminMemberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("회원 목록: 닉네임·로그인명 부분 일치와 상태·권한 조건으로 거르고, %·_는 문자 그대로 찾으며 최신 가입순으로 준다")
    void searchesWithFiltersAndLiteralWildcards() {
        Member kim = save(3001L, "kim-dev", MemberStatus.ACTIVE, MemberRole.USER);
        Member lee = save(3002L, "lee_algo", MemberStatus.SUSPENDED, MemberRole.USER);
        Member park = save(3003L, "park-100%", MemberStatus.ACTIVE, MemberRole.ADMIN);
        ReflectionTestUtils.setField(lee, "nickname", "kim_friend");
        memberRepository.saveAndFlush(lee);

        assertThat(ids(adminMemberService.searchMembers("kim", null, null, NEWEST_FIRST).getContent()))
            .containsExactly(lee.getId(), kim.getId());
        assertThat(ids(adminMemberService.searchMembers("kim", MemberStatus.ACTIVE, null, NEWEST_FIRST).getContent()))
            .containsExactly(kim.getId());
        assertThat(ids(adminMemberService.searchMembers(null, null, MemberRole.ADMIN, NEWEST_FIRST).getContent()))
            .contains(park.getId())
            .doesNotContain(kim.getId(), lee.getId());
        // 두 조건을 함께 주면 교집합만 남는다(한쪽 조건을 무시하면 kim 또는 lee가 섞인다).
        assertThat(
            ids(
                adminMemberService.searchMembers(null, MemberStatus.ACTIVE, MemberRole.ADMIN, NEWEST_FIRST).getContent()
            )
        ).contains(park.getId()).doesNotContain(kim.getId(), lee.getId());
        assertThat(
            ids(
                adminMemberService
                    .searchMembers("kim", MemberStatus.SUSPENDED, MemberRole.USER, NEWEST_FIRST)
                    .getContent()
            )
        ).containsExactly(lee.getId());
        assertThat(ids(adminMemberService.searchMembers("100%", null, null, NEWEST_FIRST).getContent()))
            .containsExactly(park.getId());
        assertThat(ids(adminMemberService.searchMembers("m_f", null, null, NEWEST_FIRST).getContent()))
            .containsExactly(lee.getId());
        assertThat(ids(adminMemberService.searchMembers("m%f", null, null, NEWEST_FIRST).getContent())).isEmpty();
    }

    @Test
    @DisplayName("정지·해제: 상태·정지 시각·profileVersion을 바꾸고 사유·집행자·IP·전후 상태를 감사 로그에, MemberSuspended를 Outbox에 남긴다")
    void changeStatusRecordsAudit() {
        Member member = save(4001L, "choi-dev", MemberStatus.ACTIVE, MemberRole.USER);
        long auditsBefore = auditLogRepository.count();

        adminMemberService.changeStatus(1L, member.getId(), MemberStatus.SUSPENDED, "부정 행위 신고 확인", "203.0.113.7");
        Member suspended = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(suspended.getStatus()).isEqualTo(MemberStatus.SUSPENDED);
        assertThat(suspended.getSuspendedAt()).isNotNull();
        assertThat(suspended.getProfileVersion()).isEqualTo(2L);

        adminMemberService.changeStatus(1L, member.getId(), MemberStatus.ACTIVE, "소명 확인", null);
        Member restored = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(restored.getSuspendedAt()).isNull();
        assertThat(restored.getProfileVersion()).isEqualTo(3L);

        List<AuditLog> audits = latestAudits(auditsBefore);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).getAction()).isEqualTo("MEMBER_SUSPEND");
        assertThat(audits.get(0).getActorId()).isEqualTo(1L);
        assertThat(audits.get(0).getTargetType()).isEqualTo("MEMBER");
        assertThat(audits.get(0).getTargetId()).isEqualTo(String.valueOf(member.getId()));
        assertThat(audits.get(0).getBeforeState()).contains("\"status\"", "ACTIVE");
        assertThat(audits.get(0).getAfterState()).contains("\"status\"", "SUSPENDED");
        assertThat(audits.get(0).getReason()).isEqualTo("부정 행위 신고 확인");
        assertThat(audits.get(0).getClientIp()).isEqualTo("203.0.113.7");
        assertThat(audits.get(0).getCreatedAt()).isNotNull();
        assertThat(audits.get(1).getAction()).isEqualTo("MEMBER_UNSUSPEND");
        assertThat(audits.get(1).getActorId()).isEqualTo(1L);
        assertThat(audits.get(1).getTargetId()).isEqualTo(String.valueOf(member.getId()));
        assertThat(audits.get(1).getBeforeState()).contains("\"status\"", "SUSPENDED");
        assertThat(audits.get(1).getAfterState()).contains("\"status\"", "ACTIVE");
        assertThat(audits.get(1).getReason()).isEqualTo("소명 확인");
        assertThat(audits.get(1).getClientIp()).isNull();

        List<JsonNode> events = memberSuspendedEvents(member.getId());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("memberId").asString()).isEqualTo(String.valueOf(member.getId()));
        assertThat(events.get(0).get("suspended").asBoolean()).isTrue();
        assertThat(events.get(0).get("sourceVersion").asLong()).isEqualTo(2L);
        assertThat(events.get(0).get("reason").asString()).isEqualTo("부정 행위 신고 확인");
        assertThat(events.get(0).get("changedAt").asString())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\+09:00");
        assertThat(events.get(1).get("suspended").asBoolean()).isFalse();
        assertThat(events.get(1).get("sourceVersion").asLong()).isEqualTo(3L);
        assertThat(events.get(1).get("reason").asString()).isEqualTo("소명 확인");
    }

    @Test
    @DisplayName("정지·해제: 같은 상태·탈퇴 회원·WITHDRAWN 대상은 COMMON_INVALID_REQUEST이고 감사 로그·이벤트를 남기지 않는다")
    void changeStatusRejectsInvalidTransition() {
        Member active = save(4002L, "jung-dev", MemberStatus.ACTIVE, MemberRole.USER);
        Member withdrawn = save(4003L, "han-dev", MemberStatus.WITHDRAWN, MemberRole.USER);
        long auditsBefore = auditLogRepository.count();

        assertInvalid(() -> adminMemberService.changeStatus(1L, active.getId(), MemberStatus.ACTIVE, "사유", null));
        assertInvalid(() -> adminMemberService.changeStatus(1L, active.getId(), MemberStatus.WITHDRAWN, "사유", null));
        assertInvalid(() -> adminMemberService.changeStatus(1L, withdrawn.getId(), MemberStatus.SUSPENDED, "사유", null));

        assertThat(auditLogRepository.count()).isEqualTo(auditsBefore);
        assertThat(memberRepository.findById(active.getId()).orElseThrow().getProfileVersion()).isEqualTo(1L);
        assertThat(memberSuspendedEvents(active.getId())).isEmpty();
        assertThat(memberSuspendedEvents(withdrawn.getId())).isEmpty();
    }

    @Test
    @DisplayName("권한 변경: 바꾸면 감사 로그를 남기고, 같은 권한이면 그대로, 본인 권한은 AUTH_ACCESS_DENIED")
    void changeRoleRecordsAuditAndRejectsSelf() {
        Member member = save(4004L, "yoon-dev", MemberStatus.ACTIVE, MemberRole.USER);
        long auditsBefore = auditLogRepository.count();

        adminMemberService.changeRole(1L, member.getId(), MemberRole.ADMIN, "운영진 합류", null);
        adminMemberService.changeRole(1L, member.getId(), MemberRole.ADMIN, "중복 요청", null);

        Member changed = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(changed.getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(changed.getProfileVersion()).isEqualTo(1L);
        List<AuditLog> audits = latestAudits(auditsBefore);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getAction()).isEqualTo("MEMBER_ROLE_CHANGE");
        assertThat(audits.get(0).getBeforeState()).contains("USER");
        assertThat(audits.get(0).getAfterState()).contains("ADMIN");

        assertThatThrownBy(
            () -> adminMemberService.changeRole(member.getId(), member.getId(), MemberRole.USER, "사유", null)
        ).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED);
    }

    private void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    private List<JsonNode> memberSuspendedEvents(Long memberId) {
        return jdbcTemplate
            .queryForList(
                "select status, payload from outbox_events "
                    + "where aggregate_type = 'MEMBER' and event_type = 'MemberSuspended' and aggregate_id = ? "
                    + "order by id",
                String.valueOf(memberId)
            )
            .stream()
            .peek(row -> assertThat(row.get("status")).isEqualTo("PENDING"))
            .map(row -> objectMapper.readTree(row.get("payload").toString()))
            .toList();
    }

    private List<AuditLog> latestAudits(long skip) {
        return auditLogRepository.findAll(Sort.by("id")).stream().skip(skip).toList();
    }

    private Member save(Long githubId, String login, MemberStatus status, MemberRole role) {
        Member member = Member.registerWithGitHub(githubId, login, null, null);
        ReflectionTestUtils.setField(member, "status", status);
        ReflectionTestUtils.setField(member, "role", role);
        // 가입 시각이 같아도 정렬의 id 보조 조건으로 나중에 저장한 회원이 먼저 온다.
        return memberRepository.saveAndFlush(member);
    }

    private List<Long> ids(List<Member> members) {
        return members.stream().map(Member::getId).toList();
    }
}
