package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.skill.client.ProblemDiagnosisClient;
import com.backtoback.member.skill.client.ProblemDiagnosisClient.Candidate;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.domain.TagLevel;
import com.backtoback.member.skill.repository.DiagnosisAttemptRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;
import com.backtoback.member.skill.service.DiagnosisService.DiagnosisDetail;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 진단 채점 결과(J5)·실패(J4)를 공통 소비기에 넣어 결과 확정·레벨 반영·M4·M5 기록(정책 및 상태 8.3, 스케줄러 1.2)을 실제 MySQL로
 * 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DiagnosisScoringTest {

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
    private EventConsumer eventConsumer;

    @Autowired
    private DiagnosisService diagnosisService;

    @Autowired
    private DiagnosisScoringScheduler scoringScheduler;

    @Autowired
    private DiagnosisAttemptRepository attemptRepository;

    @Autowired
    private MemberTagLevelRepository tagLevelRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger acks = new AtomicInteger();

    @Test
    @DisplayName("마지막 제출이 종결되면 SCORED로 확정한다: 점수 = 정답 난이도 합, 결과 = 최고 정답 난이도, 첫 진입 LV0 → 결과 레벨, M5·M4 기록")
    void scoresWhenLastResultArrives() {
        long memberId = member(9201L);
        long diagnosisId = start(memberId, 31L);
        Map<Long, Long> submissions = submitAll(memberId, diagnosisId);
        diagnosisService.finish(memberId, diagnosisId);

        consume(judged(memberId, diagnosisId, 301L, submissions.get(301L), 9001L, 1, "AC"));
        consume(judged(memberId, diagnosisId, 315L, submissions.get(315L), 9002L, 1, "WA"));
        assertThat(attempt(diagnosisId).getStatus()).isEqualTo(DiagnosisStatus.CLOSED);
        // 작거나 같은 회차는 무시한다.
        consume(judged(memberId, diagnosisId, 315L, submissions.get(315L), 9002L, 1, "AC"));
        consume(judged(memberId, diagnosisId, 330L, submissions.get(330L), 9003L, 1, "AC"));

        DiagnosisAttempt scored = attempt(diagnosisId);
        assertThat(scored.getStatus()).isEqualTo(DiagnosisStatus.SCORED);
        assertThat(scored.getScore()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(scored.getResultLevel()).isEqualTo(TagLevel.LV3);
        assertThat(scored.getAppliedLevel()).isEqualTo(TagLevel.LV3);
        assertThat(scored.isCooldownExempt()).isFalse();
        MemberTagLevel tagLevel = tagLevelRepository.findByMemberIdAndTagId(memberId, 31L).orElseThrow();
        assertThat(tagLevel.getLevel()).isEqualTo(TagLevel.LV3);
        assertThat(tagLevel.getDiagnosedLevel()).isEqualTo(TagLevel.LV3);
        assertThat(tagLevel.getLastDiagnosisEndedAt()).isEqualTo(scored.getClosedAt());
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select count(*) from member_level_histories where diagnosis_attempt_id = ? "
                        + "and reason_type = 'DIAGNOSIS' and from_level = 'LV0' and to_level = 'LV3'",
                    Long.class,
                    diagnosisId
                )
        ).isEqualTo(1L);

        List<JsonNode> levelEvents = outbox("TagLevelChanged", memberId);
        assertThat(levelEvents).hasSize(1);
        assertThat(levelEvents.get(0).get("tagName").asString()).isEqualTo("이분 탐색");
        assertThat(levelEvents.get(0).get("fromLevel").asString()).isEqualTo("LV0");
        assertThat(levelEvents.get(0).get("toLevel").asString()).isEqualTo("LV3");
        assertThat(levelEvents.get(0).get("reasonType").asString()).isEqualTo("DIAGNOSIS");
        assertThat(levelEvents.get(0).get("diagnosisId").asString()).isEqualTo(String.valueOf(diagnosisId));
        List<JsonNode> profileEvents = outbox("LearningProfileUpdated", memberId);
        assertThat(profileEvents.get(profileEvents.size() - 1).get("tagLevels").get(0).get("level").asString())
            .isEqualTo("LV3");

        DiagnosisDetail detail = diagnosisService.getDetail(memberId, diagnosisId);
        assertThat(detail.attempt().getAppliedLevel()).isEqualTo(TagLevel.LV3);
        assertThat(detail.problems()).extracting("finalVerdict").containsExactly("AC", "WA", "AC");
        assertThat(acks).hasValue(4);
    }

    @Test
    @DisplayName("같은 응시의 결과 3건이 동시에 와도 응시 잠금으로 한 번만 확정하고 TagLevelChanged도 1건이다")
    void concurrentResultsScoreOnce() throws Exception {
        long memberId = member(9207L);
        long diagnosisId = start(memberId, 37L);
        Map<Long, Long> submissions = submitAll(memberId, diagnosisId);
        diagnosisService.finish(memberId, diagnosisId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (Map.Entry<Long, Long> submission : submissions.entrySet()) {
                String message
                    = judged(
                        memberId,
                        diagnosisId,
                        submission.getKey(),
                        submission.getValue(),
                        submission.getValue() + 700,
                        1,
                        "AC"
                    );
                results.add(executor.submit(() -> {
                    start.await();
                    consume(message);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(attempt(diagnosisId).getStatus()).isEqualTo(DiagnosisStatus.SCORED);
        assertThat(attempt(diagnosisId).getScore()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(outbox("TagLevelChanged", memberId)).hasSize(1);
    }

    @Test
    @DisplayName("재응시 결과가 현재 레벨보다 낮으면 레벨을 내리지 않고 이력도 남기지 않으며, 결과 알림은 보낸다")
    void doesNotLowerLevel() {
        long memberId = member(9202L);
        MemberTagLevel entered = MemberTagLevel.enterWithoutDiagnosis(memberId, 32L, now());
        ReflectionTestUtils.setField(entered, "level", TagLevel.LV2);
        tagLevelRepository.saveAndFlush(entered);
        long diagnosisId = start(memberId, 32L);
        Map<Long, Long> submissions = submitAll(memberId, diagnosisId);
        diagnosisService.finish(memberId, diagnosisId);

        for (Map.Entry<Long, Long> submission : submissions.entrySet()) {
            consume(
                judged(
                    memberId,
                    diagnosisId,
                    submission.getKey(),
                    submission.getValue(),
                    submission.getValue() + 100,
                    1,
                    "WA"
                )
            );
        }

        DiagnosisAttempt scored = attempt(diagnosisId);
        assertThat(scored.getResultLevel()).isEqualTo(TagLevel.LV1);
        assertThat(scored.getAppliedLevel()).isEqualTo(TagLevel.LV2);
        assertThat(scored.getScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tagLevelRepository.findByMemberIdAndTagId(memberId, 32L).orElseThrow().getLevel())
            .isEqualTo(TagLevel.LV2);
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select count(*) from member_level_histories where diagnosis_attempt_id = ?",
                    Long.class,
                    diagnosisId
                )
        ).isZero();
        JsonNode levelEvent = outbox("TagLevelChanged", memberId).get(0);
        assertThat(levelEvent.get("fromLevel").asString()).isEqualTo("LV2");
        assertThat(levelEvent.get("toLevel").asString()).isEqualTo("LV2");
    }

    @Test
    @DisplayName("진단 제출의 채점 실패(J4)는 FAILED로 반영해 쿨타임을 면제하고, 다른 문맥의 실패는 무시한다")
    void failedSubmissionExemptsCooldown() {
        long memberId = member(9203L);
        long diagnosisId = start(memberId, 33L);
        Map<Long, Long> submissions = submitAll(memberId, diagnosisId);
        diagnosisService.finish(memberId, diagnosisId);

        consume(failed("PRACTICE", memberId, diagnosisId, 301L, submissions.get(301L), 9300L));
        assertThat(attempt(diagnosisId).getStatus()).isEqualTo(DiagnosisStatus.CLOSED);
        consume(failed("DIAGNOSIS", memberId, diagnosisId, 301L, submissions.get(301L), 9301L));
        consume(judged(memberId, diagnosisId, 315L, submissions.get(315L), 9302L, 1, "AC"));
        consume(judged(memberId, diagnosisId, 330L, submissions.get(330L), 9303L, 1, "WA"));

        DiagnosisAttempt scored = attempt(diagnosisId);
        assertThat(scored.getStatus()).isEqualTo(DiagnosisStatus.SCORED);
        assertThat(scored.isCooldownExempt()).isTrue();
        assertThat(scored.getResultLevel()).isEqualTo(TagLevel.LV2);
        assertThat(tagLevelRepository.findByMemberIdAndTagId(memberId, 33L).orElseThrow().getLastDiagnosisEndedAt())
            .isNull();
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select status from diagnosis_submissions where id = ?",
                    String.class,
                    submissions.get(301L)
                )
        ).isEqualTo("FAILED");
        // 쿨타임이 면제돼 바로 다시 볼 수 있다.
        assertThat(start(memberId, 33L)).isPositive();
    }

    @Test
    @DisplayName("제출 없이 종료하면 자동 제출이 없어 바로 LV1·0점으로 확정한다")
    void scoresImmediatelyWithoutSubmissions() {
        long memberId = member(9204L);
        long diagnosisId = start(memberId, 34L);

        diagnosisService.finish(memberId, diagnosisId);

        DiagnosisAttempt scored = attempt(diagnosisId);
        assertThat(scored.getStatus()).isEqualTo(DiagnosisStatus.SCORED);
        assertThat(scored.getResultLevel()).isEqualTo(TagLevel.LV1);
        assertThat(tagLevelRepository.findByMemberIdAndTagId(memberId, 34L).orElseThrow().getLevel())
            .isEqualTo(TagLevel.LV1);
    }

    @Test
    @DisplayName("결과 소비가 놓친 응시는 보정 스케줄러가 확정한다")
    void schedulerScoresMissedAttempts() {
        long memberId = member(9205L);
        long diagnosisId = start(memberId, 35L);
        submitAll(memberId, diagnosisId);
        diagnosisService.finish(memberId, diagnosisId);
        jdbcTemplate
            .update(
                "update diagnosis_submissions set status = 'JUDGED', verdict = 'WA' where diagnosis_attempt_id = ?",
                diagnosisId
            );

        scoringScheduler.score();

        assertThat(attempt(diagnosisId).getStatus()).isEqualTo(DiagnosisStatus.SCORED);
    }

    @Test
    @DisplayName("다른 회원·다른 문항의 결과와 계약과 다른 결과는 예외로 재전달에 맡기고 ACK하지 않는다")
    void rejectsMismatchedResults() {
        long memberId = member(9206L);
        long diagnosisId = start(memberId, 36L);
        Map<Long, Long> submissions = submitAll(memberId, diagnosisId);

        Map<String, Object> otherMember
            = judgedPayload(memberId + 1, diagnosisId, 301L, submissions.get(301L), 9601L, 1, "AC");
        Map<String, Object> otherProblem
            = judgedPayload(memberId, diagnosisId, 315L, submissions.get(301L), 9602L, 1, "AC");
        Map<String, Object> badStatus
            = judgedPayload(memberId, diagnosisId, 301L, submissions.get(301L), 9603L, 1, "AC");
        badStatus.put("status", "FAILED");
        for (Map<String, Object> event : List.of(otherMember, otherProblem, badStatus)) {
            assertThatThrownBy(() -> consume(objectMapper.writeValueAsString(event)))
                .isInstanceOf(RuntimeException.class);
        }
        assertThat(acks).hasValue(0);
    }

    private long start(long memberId, long tagId) {
        given(problemTagClient.findName(tagId)).willReturn(Optional.of("이분 탐색"));
        given(problemDiagnosisClient.candidates(tagId)).willReturn(CANDIDATES);
        return diagnosisService.start(memberId, tagId).attempt().getId();
    }

    private Map<Long, Long> submitAll(long memberId, long diagnosisId) {
        Map<Long, Long> submissions = new LinkedHashMap<>();
        for (long problemId : List.of(301L, 315L, 330L)) {
            submissions
                .put(problemId, diagnosisService.submit(memberId, diagnosisId, problemId, "java", "code", 1).id());
        }
        return submissions;
    }

    private String judged(
        long memberId,
        long diagnosisId,
        long problemId,
        long diagnosisSubmissionId,
        long submissionId,
        int judgeAttempt,
        String verdict
    ) {
        return objectMapper
            .writeValueAsString(
                judgedPayload(
                    memberId,
                    diagnosisId,
                    problemId,
                    diagnosisSubmissionId,
                    submissionId,
                    judgeAttempt,
                    verdict
                )
            );
    }

    private Map<String, Object> judgedPayload(
        long memberId,
        long diagnosisId,
        long problemId,
        long diagnosisSubmissionId,
        long submissionId,
        int judgeAttempt,
        String verdict
    ) {
        Map<String, Object> event = header("DiagnosisSubmissionJudged", submissionId);
        event.put("context", "DIAGNOSIS");
        event.put("diagnosisId", String.valueOf(diagnosisId));
        event.put("diagnosisSubmissionId", String.valueOf(diagnosisSubmissionId));
        event.put("submissionId", String.valueOf(submissionId));
        event.put("userId", String.valueOf(memberId));
        event.put("problemId", String.valueOf(problemId));
        event.put("problemRevisionId", "1");
        event.put("judgeAttempt", judgeAttempt);
        event.put("status", "COMPLETED");
        event.put("verdict", verdict);
        event.put("passedCount", 1);
        event.put("totalCount", 1);
        event.put("submittedAt", "2026-11-10T20:00:00.000000+09:00");
        return event;
    }

    private String failed(
        String context,
        long memberId,
        long diagnosisId,
        long problemId,
        long diagnosisSubmissionId,
        long submissionId
    ) {
        Map<String, Object> event = header("SubmissionFailed", submissionId);
        event.put("context", context);
        event.put("contextId", String.valueOf(diagnosisId));
        event.put("receiptId", String.valueOf(diagnosisSubmissionId));
        event.put("submissionId", String.valueOf(submissionId));
        event.put("userId", String.valueOf(memberId));
        event.put("problemId", String.valueOf(problemId));
        event.put("problemRevisionId", "1");
        event.put("judgeAttempt", 3);
        event.put("status", "FAILED");
        event.put("submittedAt", "2026-11-10T20:00:00.000000+09:00");
        event.put("errorCode", "JUDGE0_TIMEOUT");
        return objectMapper.writeValueAsString(event);
    }

    private Map<String, Object> header(String eventType, long submissionId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("schemaVersion", "v1");
        event.put("occurredAt", "2026-11-10T20:00:01.000000+09:00");
        event.put("aggregateId", String.valueOf(submissionId));
        event.put("correlationId", "test");
        return event;
    }

    private void consume(String message) {
        eventConsumer.consume(message, acks::incrementAndGet);
    }

    private DiagnosisAttempt attempt(long diagnosisId) {
        return attemptRepository.findById(diagnosisId).orElseThrow();
    }

    private List<JsonNode> outbox(String eventType, long memberId) {
        return jdbcTemplate
            .queryForList(
                "select payload from outbox_events where event_type = ? and aggregate_id = ? order by id",
                String.class,
                eventType,
                String.valueOf(memberId)
            )
            .stream()
            .map(objectMapper::readTree)
            .toList();
    }

    private long member(long githubId) {
        return memberRepository.saveAndFlush(Member.registerWithGitHub(githubId, "sc-" + githubId, null, null)).getId();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
    }
}
