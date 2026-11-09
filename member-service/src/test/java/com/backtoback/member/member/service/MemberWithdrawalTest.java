package com.backtoback.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.client.WithdrawalCheckClient;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 회원 탈퇴(API 명세서 1.2.3)의 차단 조건과 익명화·{@code MemberWithdrawn}(이벤트 계약 M3) 기록을 실제 MySQL로 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MemberWithdrawalTest {

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

    @MockitoBean
    private WithdrawalCheckClient withdrawalCheckClient;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("차단 조건이 없으면 WITHDRAWN·익명화·순번 증가와 MemberWithdrawn을 한 번 기록하고, 다시 탈퇴해도 아무것도 하지 않는다")
    void withdrawsAndRecordsEvent() {
        Member member
            = memberRepository
                .saveAndFlush(
                    Member.registerWithGitHub(8001L, "wd-dev", "wd@example.com", "https://avatars.example.com/8001")
                );

        memberService.withdraw(member.getId());
        memberService.withdraw(member.getId());

        Member withdrawn = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(withdrawn.getWithdrawnAt()).isNotNull();
        assertThat(withdrawn.getNickname()).isEqualTo("탈퇴한 사용자");
        assertThat(withdrawn.getGithubLogin()).isEqualTo("withdrawn-" + member.getId());
        assertThat(withdrawn.getEmail()).isNull();
        assertThat(withdrawn.getProfileImageUrl()).isNull();
        assertThat(withdrawn.getGithubId()).isEqualTo(8001L);
        assertThat(withdrawn.getProfileVersion()).isEqualTo(2L);

        List<JsonNode> events = events(member.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("memberId").asString()).isEqualTo(String.valueOf(member.getId()));
        assertThat(events.get(0).get("sourceVersion").asLong()).isEqualTo(2L);
        assertThat(events.get(0).get("withdrawnAt").asString())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\+09:00");
    }

    @Test
    @DisplayName("입장한 미확정 시험·스터디장·확인 실패는 각각 409·409·503이고 회원과 이벤트를 바꾸지 않는다")
    void blockedWithdrawalChangesNothing() {
        Long exam = save(8002L);
        Long leader = save(8003L);
        Long unavailable = save(8004L);
        given(withdrawalCheckClient.hasActiveExam(exam)).willReturn(true);
        given(withdrawalCheckClient.isStudyLeader(leader)).willReturn(true);
        given(withdrawalCheckClient.isStudyLeader(unavailable))
            .willThrow(new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));

        assertError(exam, MemberErrorCode.WITHDRAWAL_BLOCKED_ACTIVE_EXAM);
        assertError(leader, MemberErrorCode.WITHDRAWAL_BLOCKED_STUDY_LEADER);
        assertError(unavailable, CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);

        for (Long memberId : List.of(exam, leader, unavailable)) {
            Member member = memberRepository.findById(memberId).orElseThrow();
            assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(member.getProfileVersion()).isEqualTo(1L);
            assertThat(events(memberId)).isEmpty();
        }
    }

    private void assertError(Long memberId, Object errorCode) {
        assertThatThrownBy(() -> memberService.withdraw(memberId))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(errorCode);
    }

    private Long save(Long githubId) {
        return memberRepository.saveAndFlush(Member.registerWithGitHub(githubId, "wd-" + githubId, null, null)).getId();
    }

    private List<JsonNode> events(Long memberId) {
        return jdbcTemplate
            .queryForList(
                "select payload from outbox_events where aggregate_type = 'MEMBER' "
                    + "and event_type = 'MemberWithdrawn' and aggregate_id = ? order by id",
                String.class,
                String.valueOf(memberId)
            )
            .stream()
            .map(objectMapper::readTree)
            .toList();
    }
}
