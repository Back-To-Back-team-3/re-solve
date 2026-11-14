package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.skill.client.ProblemTagClient;

import tools.jackson.databind.ObjectMapper;

/**
 * {@code SubmissionJudged} v1(이벤트 계약 J1)을 공통 소비기에 넣어, 복제본 교체·성취도 재계산·멱등 처리가 실제 MySQL에서 정책 및
 * 상태 8.3대로 동작하는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PracticeResultConsumeTest {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");

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
    private EventConsumer eventConsumer;

    @Autowired
    private TagLevelService tagLevelService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger acks = new AtomicInteger();

    @Test
    @DisplayName("문제 단위로 성취도를 계산하고, 더 큰 judgeAttempt만 반영하며, 재채점으로 바뀐 난이도도 다시 계산한다")
    void recalculatesPerProblem() {
        long memberId = member(7001L);
        given(problemTagClient.exists(3L)).willReturn(true);
        tagLevelService.enter(memberId, 3L);

        consume(event(101, memberId, 11, 1, "WA", 1, List.of("3", "5")));
        assertThat(achievement(memberId, 3, 1)).containsExactly(0, "0.00", "1.00", "0.00");

        // 같은 문제를 나중에 맞히면 총점은 그대로이고 획득 점수만 는다.
        consume(event(102, memberId, 11, 1, "AC", 1, List.of("3", "5")));
        assertThat(achievement(memberId, 3, 1)).containsExactly(1, "1.00", "1.00", "100.00");

        String other = event(103, memberId, 12, 1, "WA", 1, List.of("3"));
        consume(other);
        consume(other);
        assertThat(achievement(memberId, 3, 1)).containsExactly(1, "1.00", "2.00", "50.00");
        assertThat(achievement(memberId, 5, 1)).containsExactly(1, "1.00", "1.00", "100.00");
        assertThat(acks).hasValue(4);

        // 같거나 작은 회차는 무시하고, 큰 회차는 교체한다.
        consume(event(102, memberId, 11, 1, "WA", 1, List.of("3", "5")));
        assertThat(achievement(memberId, 3, 1)).containsExactly(1, "1.00", "2.00", "50.00");
        consume(event(102, memberId, 11, 1, "WA", 2, List.of("3", "5")));
        assertThat(achievement(memberId, 3, 1)).containsExactly(0, "0.00", "2.00", "0.00");
        assertThat(achievement(memberId, 5, 1)).containsExactly(0, "0.00", "1.00", "0.00");

        // 재채점으로 난이도가 바뀌면 이전 난이도와 새 난이도를 모두 다시 계산한다.
        consume(event(103, memberId, 12, 2, "AC", 2, List.of("3")));
        assertThat(achievement(memberId, 3, 1)).containsExactly(0, "0.00", "1.00", "0.00");
        assertThat(achievement(memberId, 3, 2)).containsExactly(1, "2.00", "2.00", "100.00");

        List<TagAchievement> achievements = tagLevelService.getTagLevels(memberId).get(0).achievements();
        assertThat(achievements).extracting(TagAchievement::difficulty).containsExactly(1, 2);
        assertThat(achievements.get(0).band()).isEqualTo("WEAK");
        assertThat(achievements.get(1).achievementRate()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(achievements.get(1).band()).isEqualTo("GOOD");

        // 재채점으로 태그가 바뀌면 남은 제출이 없는 이전 태그의 성취도는 지우고 새 태그에 만든다.
        consume(event(103, memberId, 12, 2, "AC", 3, List.of("5")));
        assertThat(
            count(
                "select count(*) from member_tag_achievements where member_id = ? and tag_id = 3 and difficulty = 2",
                memberId
            )
        ).isZero();
        assertThat(achievement(memberId, 5, 2)).containsExactly(1, "2.00", "2.00", "100.00");
    }

    @Test
    @DisplayName("계약과 다른 이벤트와 모르는 회원은 예외로 재전달에 맡기고 ACK·소비 기록·복제본을 남기지 않는다")
    void rejectsInvalidEvents() throws Exception {
        long memberId = member(7002L);
        Map<String, Object> noTags = payload(201, memberId, 21, 1, "AC", 1, List.of());
        Map<String, Object> badVerdict = payload(202, memberId, 21, 1, "PASS", 1, List.of("3"));
        Map<String, Object> unknownMember = payload(203, 999_999L, 21, 1, "AC", 1, List.of("3"));

        for (Map<String, Object> event : List.of(noTags, badVerdict, unknownMember)) {
            assertThatThrownBy(() -> consume(message(event))).isInstanceOf(RuntimeException.class);
            // 다른 테스트의 소비 기록과 섞이지 않도록 이번 이벤트 ID만 확인한다.
            assertThat(count("select count(*) from processed_events where event_id = ?", event.get("eventId")))
                .isZero();
        }

        assertThat(acks).hasValue(0);
        assertThat(count("select count(*) from practice_result_replicas where submission_id between 201 and 203"))
            .isZero();
    }

    @Test
    @DisplayName("같은 회원의 결과가 동시에 와도 회원 잠금으로 성취도가 덮어써지지 않는다")
    void concurrentResultsDoNotLoseUpdates() throws Exception {
        long memberId = member(7003L);
        int problems = 5;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < problems; i++) {
            String message = event(301 + i, memberId, 31 + i, 1, "AC", 1, List.of("8"));
            tasks.add(() -> {
                start.await();
                consume(message);
                return null;
            });
        }
        ExecutorService executor = Executors.newFixedThreadPool(problems);
        try {
            List<Future<Object>> results = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Object> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(achievement(memberId, 8, 1)).containsExactly(5, "5.00", "5.00", "100.00");
    }

    private void consume(String message) {
        eventConsumer.consume(message, acks::incrementAndGet);
    }

    private long member(long githubId) {
        return memberRepository.saveAndFlush(Member.registerWithGitHub(githubId, "pr-" + githubId, null, null)).getId();
    }

    private String event(
        long submissionId,
        long memberId,
        long problemId,
        int difficulty,
        String verdict,
        int judgeAttempt,
        List<String> tagIds
    ) {
        return message(payload(submissionId, memberId, problemId, difficulty, verdict, judgeAttempt, tagIds));
    }

    private Map<String, Object> payload(
        long submissionId,
        long memberId,
        long problemId,
        int difficulty,
        String verdict,
        int judgeAttempt,
        List<String> tagIds
    ) {
        String now = EVENT_TIME.format(OffsetDateTime.parse("2026-11-09T21:00:00.123456+09:00"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "SubmissionJudged");
        event.put("schemaVersion", "v1");
        event.put("occurredAt", now);
        event.put("aggregateId", String.valueOf(submissionId));
        event.put("correlationId", "test");
        event.put("submissionId", String.valueOf(submissionId));
        event.put("userId", String.valueOf(memberId));
        event.put("problemId", String.valueOf(problemId));
        event.put("problemRevisionId", "1");
        event.put("verdict", verdict);
        event.put("judgeAttempt", judgeAttempt);
        event.put("submittedAt", "2026-11-09T20:59:00+09:00");
        event.put("judgedAt", now);
        event.put("passedCount", 1);
        event.put("totalCount", 1);
        event.put("language", "java");
        event.put("problemDifficulty", difficulty);
        event.put("problemTags", tagIds.stream().map(id -> "tag-" + id).toList());
        event.put("problemTagIds", tagIds);
        event.put("problemTitle", "문제");
        return event;
    }

    private String message(Map<String, Object> event) {
        return objectMapper.writeValueAsString(event);
    }

    // solved_count, earned_points, total_points, achievement_rate
    private List<Object> achievement(long memberId, int tagId, int difficulty) {
        return jdbcTemplate
            .queryForObject(
                "select solved_count, earned_points, total_points, achievement_rate from member_tag_achievements "
                    + "where member_id = ? and tag_id = ? and difficulty = ?",
                (row, index) -> List
                    .of(
                        row.getInt(1),
                        row.getBigDecimal(2).toPlainString(),
                        row.getBigDecimal(3).toPlainString(),
                        row.getBigDecimal(4).toPlainString()
                    ),
                memberId,
                tagId,
                difficulty
            );
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
