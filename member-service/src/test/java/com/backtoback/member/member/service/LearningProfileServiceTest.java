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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.member.domain.ActiveTimeSlot;
import com.backtoback.member.member.domain.LearningGoal;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.service.TagLevelService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 학습 프로필 저장·태그 진입이 {@code profile_version}과 {@code LearningProfileUpdated} Outbox 기록을 같은 MySQL 트랜잭션에
 * 남기는지 확인한다(API 명세서 1.3, 이벤트 계약 M4).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LearningProfileServiceTest {

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
    private ProblemTagClient problemTagClient;

    @Autowired
    private LearningProfileService learningProfileService;

    @Autowired
    private TagLevelService tagLevelService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("태그 진입만 한 회원은 미입력이고, 이후 저장·변경마다 순번을 올려 전체 스냅샷을 기록하며 같은 값 재저장은 기록하지 않는다")
    void recordsSnapshotsInOneVersionSeries() {
        Long memberId = save(6001L, "lp-dev", MemberStatus.ACTIVE);
        given(problemTagClient.exists(3L)).willReturn(true);

        tagLevelService.enter(memberId, 3L);
        assertThat(learningProfileService.getProfile(memberId)).isEmpty();
        assertThat(learningProfileService.hasProfile(memberId)).isFalse();

        long saved
            = learningProfileService
                .save(
                    memberId,
                    LearningGoal.EMPLOYMENT,
                    List.of("python3", "java", "java"),
                    List.of(ActiveTimeSlot.NIGHT, ActiveTimeSlot.MORNING)
                );
        // 순서·중복만 다른 요청은 같은 값이다.
        long unchanged
            = learningProfileService
                .save(
                    memberId,
                    LearningGoal.EMPLOYMENT,
                    List.of("java", "python3"),
                    List.of(ActiveTimeSlot.MORNING, ActiveTimeSlot.NIGHT)
                );
        long changed
            = learningProfileService
                .save(memberId, LearningGoal.STUDY, List.of("cpp"), List.of(ActiveTimeSlot.EVENING));

        assertThat(saved).isEqualTo(2L);
        assertThat(unchanged).isEqualTo(2L);
        assertThat(changed).isEqualTo(3L);
        assertThat(learningProfileService.hasProfile(memberId)).isTrue();
        LearningProfileSnapshot profile = learningProfileService.getProfile(memberId).orElseThrow();
        assertThat(profile.goal()).isEqualTo(LearningGoal.STUDY);
        assertThat(profile.preferredLanguages()).containsExactly("cpp");
        assertThat(profile.activeTimeSlots()).containsExactly("EVENING");
        assertThat(profile.tagLevels()).extracting("tagId").containsExactly(3L);
        assertThat(profile.profileVersion()).isEqualTo(3L);
        assertThat(profile.updatedAt()).isNotNull();

        List<JsonNode> events = events(memberId);
        assertThat(events).hasSize(3);
        JsonNode entry = events.get(0);
        assertThat(entry.get("memberId").asString()).isEqualTo(String.valueOf(memberId));
        assertThat(entry.get("profileVersion").asLong()).isEqualTo(1L);
        assertThat(entry.get("goal").isNull()).isTrue();
        assertThat(entry.get("preferredLanguages")).isEmpty();
        assertThat(entry.get("activeTimeSlots")).isEmpty();
        assertThat(entry.get("tagLevels").get(0).get("tagId").asString()).isEqualTo("3");
        assertThat(entry.get("tagLevels").get(0).get("level").asString()).isEqualTo("LV1");
        JsonNode first = events.get(1);
        assertThat(first.get("profileVersion").asLong()).isEqualTo(2L);
        assertThat(first.get("goal").asString()).isEqualTo("EMPLOYMENT");
        assertThat(first.get("preferredLanguages").toString()).isEqualTo("[\"java\",\"python3\"]");
        assertThat(first.get("activeTimeSlots").toString()).isEqualTo("[\"MORNING\",\"NIGHT\"]");
        assertThat(first.get("tagLevels")).hasSize(1);
        assertThat(events.get(2).get("profileVersion").asLong()).isEqualTo(3L);
        assertThat(events.get(2).get("goal").asString()).isEqualTo("STUDY");
    }

    @Test
    @DisplayName("정지 회원의 저장은 MEMBER_SUSPENDED이고 프로필 행과 이벤트를 남기지 않는다")
    void suspendedMemberCannotSave() {
        Long memberId = save(6002L, "lp-suspended", MemberStatus.SUSPENDED);

        assertThatThrownBy(
            () -> learningProfileService
                .save(memberId, LearningGoal.STUDY, List.of("java"), List.of(ActiveTimeSlot.EVENING))
        ).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_SUSPENDED);

        assertThat(
            jdbcTemplate
                .queryForObject("select count(*) from learning_profiles where member_id = ?", Long.class, memberId)
        ).isZero();
        assertThat(events(memberId)).isEmpty();
    }

    private Long save(Long githubId, String login, MemberStatus status) {
        Member member = Member.registerWithGitHub(githubId, login, null, null);
        ReflectionTestUtils.setField(member, "status", status);
        return memberRepository.saveAndFlush(member).getId();
    }

    private List<JsonNode> events(Long memberId) {
        return jdbcTemplate
            .queryForList(
                "select payload from outbox_events where aggregate_type = 'MEMBER' "
                    + "and event_type = 'LearningProfileUpdated' and aggregate_id = ? order by id",
                String.class,
                String.valueOf(memberId)
            )
            .stream()
            .map(objectMapper::readTree)
            .toList();
    }
}
