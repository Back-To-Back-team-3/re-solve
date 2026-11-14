package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;
import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.skill.client.ProblemDiagnosisClient;
import com.backtoback.member.skill.client.ProblemDiagnosisClient.Candidate;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.repository.DiagnosisAttemptRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;
import com.backtoback.member.skill.store.DiagnosisSubmissionStore.Submission;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 진단 문항 제출·최종 제출·만료 자동 제출(API 명세서 1.5.5·1.5.6, 스케줄러 1.1)과 {@code DiagnosisSubmissionRequested}(이벤트 계약
 * M6) 기록을 실제 MySQL로 확인한다. 테스트가 만드는 시각은 서비스 Clock과 같은 서울 시간이다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DiagnosisSubmitTest {

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
    private DiagnosisExpiryScheduler expiryScheduler;

    @Autowired
    private DiagnosisSubmissionRequestedCallback requestedCallback;

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

    @Test
    @DisplayName("문항 제출은 Draft 반영·ACCEPTED 접수·M6를 한 번에 남기고, 같은 seq는 기존 접수를, 5초 안의 재제출은 429를 준다")
    void submitsAndRequestsJudging() {
        long memberId = member(9101L);
        long diagnosisId = start(memberId, 21L);

        Submission first = diagnosisService.submit(memberId, diagnosisId, 301L, "python3", "print(1)", 5);
        Submission again = diagnosisService.submit(memberId, diagnosisId, 301L, "python3", "print(1)", 5);

        assertThat(first.status()).isEqualTo("ACCEPTED");
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(diagnosisService.getDetail(memberId, diagnosisId).drafts().get(301L).seq()).isEqualTo(5L);
        List<JsonNode> events = events(diagnosisId);
        assertThat(events).hasSize(1);
        JsonNode event = events.get(0);
        assertThat(event.get("aggregateId").asString()).isEqualTo(String.valueOf(first.id()));
        assertThat(event.get("diagnosisId").asString()).isEqualTo(String.valueOf(diagnosisId));
        assertThat(event.get("diagnosisSubmissionId").asString()).isEqualTo(String.valueOf(first.id()));
        assertThat(event.get("userId").asString()).isEqualTo(String.valueOf(memberId));
        assertThat(event.get("problemRevisionId").asString()).isEqualTo("1001");
        assertThat(event.get("language").asString()).isEqualTo("python3");
        assertThat(event.get("sourceCode").asString()).isEqualTo("print(1)");
        assertThat(event.get("draftSeq").asString()).isEqualTo("5");
        assertThat(event.get("submissionType").asString()).isEqualTo("MANUAL");
        assertThat(event.get("receivedAt").asString())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\+09:00");

        assertError(
            () -> diagnosisService.submit(memberId, diagnosisId, 301L, "python3", "print(2)", 6),
            MemberErrorCode.SUBMISSION_RATE_LIMITED
        );
        assertError(
            () -> diagnosisService.submit(memberId, diagnosisId, 315L, "kotlin", "x", 1),
            MemberErrorCode.UNSUPPORTED_LANGUAGE
        );
        assertError(
            () -> diagnosisService.submit(memberId, diagnosisId, 999L, "java", "x", 1),
            MemberErrorCode.DIAGNOSIS_NOT_FOUND
        );
    }

    @Test
    @DisplayName("최종 제출은 CLOSED(SUBMITTED)로 닫고 쿨타임을 시작하며, 제출 이후 바뀐 Draft만 AUTO로 자동 제출하고, 이후 제출은 거절한다")
    void finishAutoSubmitsChangedDrafts() {
        long memberId = member(9102L);
        long diagnosisId = start(memberId, 22L);
        long manual = diagnosisService.submit(memberId, diagnosisId, 301L, "java", "v5", 5).id();
        diagnosisService.saveDraft(memberId, diagnosisId, 301L, "java", "v6", 6);
        diagnosisService.saveDraft(memberId, diagnosisId, 315L, "cpp", "only-draft", 3);
        diagnosisService.saveDraft(memberId, diagnosisId, 330L, "java", "   ", 2);

        DiagnosisAttempt closed = diagnosisService.finish(memberId, diagnosisId);

        assertThat(closed.getStatus()).isEqualTo(DiagnosisStatus.CLOSED);
        assertThat(closed.getCloseReason()).isEqualTo("SUBMITTED");
        assertThat(tagLevelRepository.findByMemberIdAndTagId(memberId, 22L).orElseThrow().getLastDiagnosisEndedAt())
            .isEqualTo(closed.getClosedAt());
        List<Map<String, Object>> autos
            = jdbcTemplate
                .queryForList(
                    "select problem_id, draft_seq, received_at from diagnosis_submissions "
                        + "where diagnosis_attempt_id = ? and submission_type = 'AUTO' order by problem_id",
                    diagnosisId
                );
        assertThat(autos).extracting(row -> ((Number)row.get("problem_id")).longValue()).containsExactly(301L, 315L);
        assertThat(autos).extracting(row -> ((Number)row.get("draft_seq")).longValue()).containsExactly(6L, 3L);
        assertThat(events(diagnosisId)).hasSize(3);

        // 재전송은 종료 뒤에도 기존 접수를 돌려주고, 새 제출·재종료는 거절한다.
        assertThat(diagnosisService.submit(memberId, diagnosisId, 301L, "java", "v5", 5).id()).isEqualTo(manual);
        assertError(
            () -> diagnosisService.submit(memberId, diagnosisId, 315L, "cpp", "late", 9),
            MemberErrorCode.DIAGNOSIS_CLOSED
        );
        assertError(() -> diagnosisService.finish(memberId, diagnosisId), MemberErrorCode.DIAGNOSIS_CLOSED);
        assertThatThrownBy(() -> diagnosisService.start(memberId, 22L)).isInstanceOf(DiagnosisCooldownException.class);
    }

    @Test
    @DisplayName("만료 스케줄러는 제한 시각이 지난 진단을 EXPIRED로 닫고, 종료·자동 제출 시각을 실행 시각이 아니라 ends_at으로 기록한다")
    void expiresWithEndsAt() {
        long memberId = member(9103L);
        long diagnosisId = start(memberId, 23L);
        diagnosisService.saveDraft(memberId, diagnosisId, 301L, "java", "draft", 4);
        LocalDateTime endsAt = now().minusSeconds(10).withNano(0);
        jdbcTemplate.update("update diagnosis_attempts set ends_at = ? where id = ?", endsAt, diagnosisId);

        expiryScheduler.expire();

        DiagnosisAttempt expired = attemptRepository.findById(diagnosisId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(DiagnosisStatus.CLOSED);
        assertThat(expired.getCloseReason()).isEqualTo("EXPIRED");
        assertThat(expired.getClosedAt()).isEqualTo(endsAt);
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select received_at from diagnosis_submissions "
                        + "where diagnosis_attempt_id = ? and submission_type = 'AUTO'",
                    LocalDateTime.class,
                    diagnosisId
                )
        ).isEqualTo(endsAt);
        assertThat(events(diagnosisId).get(0).get("submissionType").asString()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("최종 제출과 만료가 동시에 와도 한 번만 닫히고 자동 제출도 한 번만 생긴다")
    void finishAndExpireCloseOnce() throws Exception {
        long memberId = member(9104L);
        long diagnosisId = start(memberId, 24L);
        diagnosisService.saveDraft(memberId, diagnosisId, 301L, "java", "draft", 4);
        jdbcTemplate
            .update("update diagnosis_attempts set ends_at = ? where id = ?", now().minusSeconds(10), diagnosisId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Object>> results = List.of(executor.submit(() -> {
                start.await();
                return diagnosisService.finish(memberId, diagnosisId);
            }), executor.submit(() -> {
                start.await();
                return diagnosisService.expire(diagnosisId);
            }), executor.submit(() -> {
                start.await();
                return diagnosisService.expire(diagnosisId);
            }), executor.submit(() -> {
                start.await();
                return diagnosisService.finish(memberId, diagnosisId);
            }));
            start.countDown();
            for (Future<Object> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode")
                        .isEqualTo(MemberErrorCode.DIAGNOSIS_CLOSED);
                }
            }
        } finally {
            executor.shutdown();
        }

        assertThat(attemptRepository.findById(diagnosisId).orElseThrow().getStatus()).isEqualTo(DiagnosisStatus.CLOSED);
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select count(*) from diagnosis_submissions where diagnosis_attempt_id = ?",
                    Long.class,
                    diagnosisId
                )
        ).isEqualTo(1L);
        assertThat(events(diagnosisId)).hasSize(1);
    }

    @Test
    @DisplayName("M6 전송 성공 콜백은 ACCEPTED만 REQUESTED로 바꾸고, 먼저 도착한 채점 결과는 덮지 않는다")
    void callbackMarksRequested() {
        long memberId = member(9105L);
        long diagnosisId = start(memberId, 25L);
        long accepted = diagnosisService.submit(memberId, diagnosisId, 301L, "java", "a", 1).id();
        long judged = diagnosisService.submit(memberId, diagnosisId, 315L, "java", "b", 1).id();
        jdbcTemplate.update("update diagnosis_submissions set status = 'JUDGED' where id = ?", judged);

        requestedCallback.onPublished(published("DiagnosisSubmissionRequested", accepted));
        requestedCallback.onPublished(published("DiagnosisSubmissionRequested", judged));
        requestedCallback.onPublished(published("MemberUpdated", accepted));

        assertThat(status(accepted)).isEqualTo("REQUESTED");
        assertThat(status(judged)).isEqualTo("JUDGED");
    }

    private long start(long memberId, long tagId) {
        given(problemTagClient.findName(tagId)).willReturn(Optional.of("태그"));
        given(problemDiagnosisClient.candidates(tagId)).willReturn(CANDIDATES);
        return diagnosisService.start(memberId, tagId).attempt().getId();
    }

    private long member(long githubId) {
        return memberRepository.saveAndFlush(Member.registerWithGitHub(githubId, "ds-" + githubId, null, null)).getId();
    }

    private List<JsonNode> events(long diagnosisId) {
        return jdbcTemplate
            .queryForList(
                "select payload from outbox_events where aggregate_type = 'DIAGNOSIS_SUBMISSION' "
                    + "and event_type = 'DiagnosisSubmissionRequested' order by id",
                String.class
            )
            .stream()
            .map(objectMapper::readTree)
            .filter(event -> event.get("diagnosisId").asString().equals(String.valueOf(diagnosisId)))
            .toList();
    }

    private String status(long submissionId) {
        return jdbcTemplate
            .queryForObject("select status from diagnosis_submissions where id = ?", String.class, submissionId);
    }

    private OutboxEvent published(String eventType, long aggregateId) {
        EventMetadata metadata
            = new EventMetadata(
                UUID.randomUUID(),
                eventType,
                "v1",
                OffsetDateTime.now(ClockConfiguration.SERVICE_ZONE),
                String.valueOf(aggregateId),
                "test"
            );
        return new OutboxEvent(1L, "DIAGNOSIS_SUBMISSION", metadata, "{}", 0);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
    }

    private void assertError(ThrowingCallable call, Object errorCode) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(errorCode);
    }
}
