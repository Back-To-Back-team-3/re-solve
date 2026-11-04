package com.backtoback.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.member.auth.client.GitHubUser;
import com.backtoback.member.member.domain.Member;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 회원 변경과 {@code MemberUpdated} Outbox 기록이 같은 MySQL 트랜잭션에 저장되는지 확인한다(이벤트 계약 M1).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MemberEventOutboxTest {

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
    private MemberService memberService;

    @MockitoSpyBean
    private MemberEventPublisher memberEventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("가입과 닉네임 변경은 MemberUpdated를 PENDING으로 기록하고, 같은 닉네임 재저장과 기존 회원 로그인은 기록하지 않는다")
    void recordsMemberUpdated() {
        Member member
            = memberService
                .findOrRegister(new GitHubUser(5001L, "seo-dev", null, "https://avatars.githubusercontent.com/u/5001"))
                .member();
        memberService.findOrRegister(new GitHubUser(5001L, "seo-dev", null, null));
        memberService.updateNickname(member.getId(), "seo-algo");
        memberService.updateNickname(member.getId(), "seo-algo");

        List<Map<String, Object>> rows
            = jdbcTemplate
                .queryForList(
                    "select aggregate_type, aggregate_id, event_type, schema_version, status, payload "
                        + "from outbox_events where aggregate_id = ? order by id",
                    String.valueOf(member.getId())
                );
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("aggregate_type")).isEqualTo("MEMBER");
            assertThat(row.get("event_type")).isEqualTo("MemberUpdated");
            assertThat(row.get("schema_version")).isEqualTo("v1");
            assertThat(row.get("status")).isEqualTo("PENDING");
        });

        JsonNode signup = objectMapper.readTree(rows.get(0).get("payload").toString());
        assertThat(signup.get("memberId").asString()).isEqualTo(String.valueOf(member.getId()));
        assertThat(signup.get("sourceVersion").asLong()).isEqualTo(1L);
        assertThat(signup.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(signup.get("nickname").asString()).isEqualTo("seo-dev");
        assertThat(signup.get("profileImageUrl").asString()).isEqualTo("https://avatars.githubusercontent.com/u/5001");
        assertThat(signup.get("eventType").asString()).isEqualTo("MemberUpdated");
        assertThat(signup.get("correlationId").asString()).isNotBlank();

        JsonNode rename = objectMapper.readTree(rows.get(1).get("payload").toString());
        assertThat(rename.get("sourceVersion").asLong()).isEqualTo(2L);
        assertThat(rename.get("nickname").asString()).isEqualTo("seo-algo");
    }

    @Test
    @DisplayName("가입 중 MemberUpdated 기록이 실패하면 회원과 Outbox 행이 모두 남지 않는다")
    void signupRollsBackWhenOutboxFails() {
        doThrow(new IllegalStateException("outbox unavailable")).when(memberEventPublisher).memberUpdated(any());
        Long outboxBefore = jdbcTemplate.queryForObject("select count(*) from outbox_events", Long.class);

        assertThatThrownBy(() -> memberService.findOrRegister(new GitHubUser(5002L, "ryu-dev", null, null)))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("select count(*) from members where github_id = 5002", Long.class))
            .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbox_events", Long.class))
            .isEqualTo(outboxBefore);
    }
}
