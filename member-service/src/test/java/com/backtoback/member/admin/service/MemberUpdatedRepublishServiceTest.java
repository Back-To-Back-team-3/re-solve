package com.backtoback.member.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.member.admin.domain.AuditLog;
import com.backtoback.member.admin.repository.AuditLogRepository;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 커서 조회·청크 커밋·Outbox JSON은 MySQL에 의존하므로 실제 MySQL로 검증한다. 회원 전체가 대상이라 테스트마다 테이블을 비운다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MemberUpdatedRepublishServiceTest {

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
    private MemberUpdatedRepublishService republishService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("delete from outbox_events");
        jdbcTemplate.update("delete from audit_logs");
        jdbcTemplate.update("delete from members");
    }

    @Test
    @DisplayName("재발행: 탈퇴 회원을 빼고 활성·정지 회원마다 현재 profileVersion의 MemberUpdated를 남기고 회원 행은 바꾸지 않는다")
    void republishesNonWithdrawnMembersWithCurrentVersion() {
        Member active = save(5001L, "active-dev", MemberStatus.ACTIVE, 3L);
        Member suspended = save(5002L, "suspended-dev", MemberStatus.SUSPENDED, 5L);
        Member withdrawn = save(5003L, "withdrawn-dev", MemberStatus.WITHDRAWN, 7L);
        List<Object> updatedAtBefore = memberUpdatedAts();

        long publishedCount = republishService.republishAll(1L, "기존 회원 복제본 보충", "203.0.113.7");

        assertThat(publishedCount).isEqualTo(2L);
        List<JsonNode> events = memberUpdatedEvents();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("memberId").asString()).isEqualTo(String.valueOf(active.getId()));
        assertThat(events.get(0).get("sourceVersion").asLong()).isEqualTo(3L);
        assertThat(events.get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(events.get(0).get("nickname").asString()).isEqualTo("active-dev");
        assertThat(events.get(1).get("memberId").asString()).isEqualTo(String.valueOf(suspended.getId()));
        assertThat(events.get(1).get("sourceVersion").asLong()).isEqualTo(5L);
        assertThat(events.get(1).get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(events)
            .extracting(event -> event.get("memberId").asString())
            .doesNotContain(String.valueOf(withdrawn.getId()));
        assertThat(memberRepository.findById(active.getId()).orElseThrow().getProfileVersion()).isEqualTo(3L);
        assertThat(memberRepository.findById(suspended.getId()).orElseThrow().getProfileVersion()).isEqualTo(5L);
        assertThat(memberUpdatedAts()).isEqualTo(updatedAtBefore);

        List<AuditLog> audits = auditLogRepository.findAll();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getAction()).isEqualTo("MEMBER_UPDATED_REPUBLISH");
        assertThat(audits.get(0).getActorId()).isEqualTo(1L);
        assertThat(audits.get(0).getTargetType()).isEqualTo("MEMBER");
        assertThat(audits.get(0).getTargetId()).isEqualTo("ALL");
        assertThat(audits.get(0).getBeforeState()).isNull();
        assertThat(audits.get(0).getAfterState()).isNull();
        assertThat(audits.get(0).getReason()).isEqualTo("기존 회원 복제본 보충");
        assertThat(audits.get(0).getClientIp()).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("재발행: 대상 회원이 없으면 0건을 반환하고 감사 로그만 1건 남긴다")
    void recordsAuditEvenWithoutTargets() {
        save(5101L, "withdrawn-only", MemberStatus.WITHDRAWN, 2L);

        long publishedCount = republishService.republishAll(1L, "기존 회원 복제본 보충", null);

        assertThat(publishedCount).isZero();
        assertThat(memberUpdatedEvents()).isEmpty();
        assertThat(auditLogRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("재발행: 청크 크기(500)를 넘는 회원 501명을 다음 청크까지 이어서 모두 발행한다")
    void continuesToNextChunk() {
        insertActiveMembers(MemberUpdatedRepublishWriter.CHUNK_SIZE + 1);

        long publishedCount = republishService.republishAll(1L, "기존 회원 복제본 보충", null);

        assertThat(publishedCount).isEqualTo(501L);
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select count(distinct aggregate_id) from outbox_events where event_type = 'MemberUpdated'",
                    Long.class
                )
        ).isEqualTo(501L);
        assertThat(auditLogRepository.count()).isEqualTo(1L);
    }

    private Member save(Long githubId, String login, MemberStatus status, long profileVersion) {
        Member member = Member.registerWithGitHub(githubId, login, null, null);
        ReflectionTestUtils.setField(member, "status", status);
        ReflectionTestUtils.setField(member, "role", MemberRole.USER);
        ReflectionTestUtils.setField(member, "profileVersion", profileVersion);
        return memberRepository.saveAndFlush(member);
    }

    private void insertActiveMembers(int count) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.of(2026, 11, 17, 10, 0));
        jdbcTemplate
            .batchUpdate(
                "insert into members (github_id, github_login, nickname, role, status, profile_version, "
                    + "created_at, updated_at) values (?, ?, ?, 'USER', 'ACTIVE', 1, ?, ?)",
                IntStream
                    .rangeClosed(1, count)
                    .mapToObj(
                        i -> new Object[] {
                            900_000L + i,
                            "bulk-" + i,
                            "bulk-" + i,
                            now,
                            now
                        }
                    )
                    .toList()
            );
    }

    private List<JsonNode> memberUpdatedEvents() {
        return jdbcTemplate
            .queryForList("select status, payload from outbox_events where event_type = 'MemberUpdated' order by id")
            .stream()
            .peek(row -> assertThat(row.get("status")).isEqualTo("PENDING"))
            .map(row -> objectMapper.readTree(row.get("payload").toString()))
            .toList();
    }

    private List<Object> memberUpdatedAts() {
        return jdbcTemplate.queryForList("select updated_at from members order by id", Object.class);
    }
}
