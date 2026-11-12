package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.skill.client.ProblemDiagnosisClient;
import com.backtoback.member.skill.client.ProblemDiagnosisClient.Candidate;
import com.backtoback.member.skill.client.ProblemTagClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 진단 쿨타임 해제 알림(스케줄러 1.3)의 대상 선정과 1회 발송을 실제 MySQL로 확인한다. 진단은 제출 없이 종료해 바로 {@code LV1}로
 * 확정하고, 종료 시각만 과거로 옮긴다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DiagnosisCooldownReleaseTest {

    private static final List<Candidate> CANDIDATES
        = List
            .of(
                new Candidate(1, "301", "1001", "쉬움"),
                new Candidate(2, "315", "1040", "중간"),
                new Candidate(3, "330", "1092", "어려움")
            );

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
        registry.add("resolve.skill.diagnosis-enabled", () -> "true");
    }

    @MockitoBean
    private ProblemTagClient problemTagClient;

    @MockitoBean
    private ProblemDiagnosisClient problemDiagnosisClient;

    @Autowired
    private DiagnosisService diagnosisService;

    @Autowired
    private DiagnosisCooldownReleaseScheduler releaseScheduler;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("쿨타임이 지난 태그별 마지막 SCORED 응시(LV1·LV2, 면제 아님)만 COOLDOWN_RELEASED로 한 번 알린다")
    void releasesOnlyEligibleAttempts() {
        long released = scoredMember(9301L, 41L, 4);
        long notYet = scoredMember(9302L, 42L, 1);
        long exempt = scoredMember(9303L, 43L, 4);
        jdbcTemplate.update("update diagnosis_attempts set is_cooldown_exempt = true where member_id = ?", exempt);
        long topLevel = scoredMember(9304L, 44L, 4);
        jdbcTemplate.update("update member_tag_levels set level = 'LV3' where member_id = ?", topLevel);
        // 쿨타임이 끝나 새 진단을 시작했으면 이전 응시는 마지막 응시가 아니라 빠진다.
        long restarted = scoredMember(9305L, 45L, 4);
        jdbcTemplate
            .update(
                "update member_tag_levels set last_diagnosis_ended_at = ? where member_id = ?",
                now().minusDays(4),
                restarted
            );
        diagnosisService.start(restarted, 45L);

        releaseScheduler.release();
        releaseScheduler.release();

        List<JsonNode> events = cooldownEvents(released);
        assertThat(events).hasSize(1);
        JsonNode event = events.get(0);
        assertThat(event.get("tagId").asString()).isEqualTo("41");
        assertThat(event.get("tagName").asString()).isEqualTo("그래프");
        assertThat(event.get("fromLevel").asString()).isEqualTo("LV1");
        assertThat(event.get("toLevel").asString()).isEqualTo("LV1");
        assertThat(event.get("diagnosisId").isNull()).isFalse();
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select count(*) from diagnosis_attempts where member_id = ? and cooldown_notified_at is not null",
                    Long.class,
                    released
                )
        ).isEqualTo(1L);
        for (long memberId : List.of(notYet, exempt, topLevel, restarted)) {
            assertThat(cooldownEvents(memberId)).isEmpty();
        }
    }

    // 제출 없이 종료하면 바로 LV1로 확정된다. 종료 시각만 daysAgo일 전으로 옮긴다.
    private long scoredMember(long githubId, long tagId, int daysAgo) {
        long memberId
            = memberRepository.saveAndFlush(Member.registerWithGitHub(githubId, "cd-" + githubId, null, null)).getId();
        given(problemTagClient.findName(tagId)).willReturn(Optional.of("그래프"));
        given(problemDiagnosisClient.candidates(tagId)).willReturn(CANDIDATES);
        long diagnosisId = diagnosisService.start(memberId, tagId).attempt().getId();
        diagnosisService.finish(memberId, diagnosisId);
        jdbcTemplate
            .update("update diagnosis_attempts set closed_at = ? where id = ?", now().minusDays(daysAgo), diagnosisId);
        return memberId;
    }

    private List<JsonNode> cooldownEvents(long memberId) {
        return jdbcTemplate
            .queryForList(
                "select payload from outbox_events where event_type = 'TagLevelChanged' "
                    + "and aggregate_id = ? order by id",
                String.class,
                String.valueOf(memberId)
            )
            .stream()
            .map(objectMapper::readTree)
            .filter(event -> "COOLDOWN_RELEASED".equals(event.get("reasonType").asString()))
            .toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
    }
}
