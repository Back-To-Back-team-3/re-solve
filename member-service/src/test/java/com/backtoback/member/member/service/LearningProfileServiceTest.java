package com.backtoback.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    // OutboxEventWriter는 MANDATORY 트랜잭션 프록시라 테스트 밖에서 스텁할 수 없어, 그 바로 앞의 발행 지점에 실패를 넣는다.
    @MockitoSpyBean
    private MemberEventPublisher memberEventPublisher;

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
    @DisplayName("태그 진입만 한 회원은 미입력이고, 저장·변경·이후 태그 진입마다 순번을 올려 전체 스냅샷을 기록하며 같은 값 재저장은 기록하지 않는다")
    void recordsSnapshotsInOneVersionSeries() {
        Long memberId = save(6001L, "lp-dev", MemberStatus.ACTIVE);
        given(problemTagClient.exists(3L)).willReturn(true);
        given(problemTagClient.exists(5L)).willReturn(true);

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
        // 프로필을 입력한 뒤의 태그 진입은 기존 항목을 유지한 채 순번만 올린다.
        tagLevelService.enter(memberId, 5L);

        assertThat(saved).isEqualTo(2L);
        assertThat(unchanged).isEqualTo(2L);
        assertThat(changed).isEqualTo(3L);
        assertThat(learningProfileService.hasProfile(memberId)).isTrue();
        LearningProfileSnapshot profile = learningProfileService.getProfile(memberId).orElseThrow();
        assertThat(profile.goal()).isEqualTo(LearningGoal.STUDY);
        assertThat(profile.preferredLanguages()).containsExactly("cpp");
        assertThat(profile.activeTimeSlots()).containsExactly("EVENING");
        assertThat(profile.tagLevels()).extracting("tagId").containsExactly(3L, 5L);
        assertThat(profile.profileVersion()).isEqualTo(4L);
        assertThat(profile.updatedAt()).isNotNull();

        List<JsonNode> events = events(memberId);
        assertThat(events).hasSize(4);
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
        JsonNode laterEntry = events.get(3);
        assertThat(laterEntry.get("profileVersion").asLong()).isEqualTo(4L);
        assertThat(laterEntry.get("goal").asString()).isEqualTo("STUDY");
        assertThat(laterEntry.get("preferredLanguages").toString()).isEqualTo("[\"cpp\"]");
        assertThat(laterEntry.get("activeTimeSlots").toString()).isEqualTo("[\"EVENING\"]");
        assertThat(laterEntry.get("tagLevels")).hasSize(2);
        assertThat(laterEntry.get("tagLevels").get(1).get("tagId").asString()).isEqualTo("5");
    }

    @Test
    @DisplayName("Outbox 기록이 실패하면 저장과 태그 진입 모두 예외가 전파되고 프로필·태그 레벨·이력·이벤트 행이 남지 않는다")
    void rollsBackWhenOutboxFails() {
        Long memberId = save(6003L, "lp-rollback", MemberStatus.ACTIVE);
        given(problemTagClient.exists(3L)).willReturn(true);
        doThrow(new IllegalStateException("outbox unavailable"))
            .when(memberEventPublisher)
            .learningProfileUpdated(any());
        long historiesBefore = count("select count(*) from member_level_histories");

        assertThatThrownBy(
            () -> learningProfileService
                .save(memberId, LearningGoal.STUDY, List.of("java"), List.of(ActiveTimeSlot.EVENING))
        ).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tagLevelService.enter(memberId, 3L)).isInstanceOf(IllegalStateException.class);

        assertThat(count("select count(*) from learning_profiles where member_id = ?", memberId)).isZero();
        assertThat(count("select count(*) from member_tag_levels where member_id = ?", memberId)).isZero();
        assertThat(count("select count(*) from member_level_histories")).isEqualTo(historiesBefore);
        assertThat(events(memberId)).isEmpty();
    }

    @Test
    @DisplayName("같은 회원의 저장과 서로 다른 태그 진입이 동시에 와도 교착 없이 모두 성공하고 순번 1·2·3을 하나씩 쓴다")
    void concurrentChangesShareOneVersionSeries() throws Exception {
        Long memberId = save(6004L, "lp-concurrent", MemberStatus.ACTIVE);
        given(problemTagClient.exists(3L)).willReturn(true);
        given(problemTagClient.exists(5L)).willReturn(true);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> {
            start.await();
            return learningProfileService
                .save(memberId, LearningGoal.STUDY, List.of("java"), List.of(ActiveTimeSlot.EVENING));
        });
        tasks.add(() -> {
            start.await();
            return tagLevelService.enter(memberId, 3L);
        });
        tasks.add(() -> {
            start.await();
            return tagLevelService.enter(memberId, 5L);
        });

        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> results = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Object> result : results) {
                // 교착이면 MySQL이 한쪽을 롤백해 예외가 난다.
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        LearningProfileSnapshot profile = learningProfileService.getProfile(memberId).orElseThrow();
        assertThat(profile.profileVersion()).isEqualTo(3L);
        assertThat(profile.tagLevels()).extracting("tagId").containsExactly(3L, 5L);
        assertThat(events(memberId))
            .extracting(event -> event.get("profileVersion").asLong())
            .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    @Test
    @DisplayName("정지 회원의 저장은 MEMBER_SUSPENDED이고 프로필 행과 이벤트를 남기지 않는다")
    void suspendedMemberCannotSave() {
        Long memberId = save(6002L, "lp-suspended", MemberStatus.SUSPENDED);

        assertThatThrownBy(
            () -> learningProfileService
                .save(memberId, LearningGoal.STUDY, List.of("java"), List.of(ActiveTimeSlot.EVENING))
        ).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_SUSPENDED);

        assertThat(count("select count(*) from learning_profiles where member_id = ?", memberId)).isZero();
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
