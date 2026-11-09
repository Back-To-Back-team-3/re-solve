package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.skill.client.ProblemDiagnosisClient;
import com.backtoback.member.skill.client.ProblemDiagnosisClient.Candidate;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.domain.TagLevel;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;
import com.backtoback.member.skill.service.DiagnosisService.DiagnosisDetail;
import com.backtoback.member.skill.service.DiagnosisService.StartedDiagnosis;
import com.backtoback.member.skill.store.DiagnosisDraftStore.Draft;
import com.backtoback.member.skill.store.DiagnosisDraftStore.SaveResult;

/**
 * 진단 시작·Draft·이력(API 명세서 1.5.1·1.5.2·1.5.4·1.5.7)을 실제 MySQL로 확인한다. problem 호출은 대역으로 바꾼다.
 * 테스트가 만드는 시각은 서비스 Clock과 같은 서울 시간이어야 한다(CI 서버는 UTC).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DiagnosisServiceTest {

    private static final List<Candidate> CANDIDATES
        = List
            .of(
                new Candidate(1, "301", "1001", "두 수의 합"),
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
    }

    @MockitoBean
    private ProblemTagClient problemTagClient;

    @MockitoBean
    private ProblemDiagnosisClient problemDiagnosisClient;

    @Autowired
    private DiagnosisService diagnosisService;

    @Autowired
    private TagLevelService tagLevelService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberTagLevelRepository tagLevelRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("처음 진입한 태그는 LV0으로 두고 난이도 1·2·3 문항을 고정해 60분 진단을 시작하며, 태그 레벨 목록에 진행 중으로 보인다")
    void startsDiagnosis() {
        Long memberId = member(9001L, MemberStatus.ACTIVE);
        given(problemTagClient.exists(3L)).willReturn(true);
        given(problemDiagnosisClient.candidates(3L)).willReturn(CANDIDATES);

        StartedDiagnosis started = diagnosisService.start(memberId, 3L);

        DiagnosisAttempt attempt = started.attempt();
        assertThat(attempt.getStatus()).isEqualTo(DiagnosisStatus.IN_PROGRESS);
        assertThat(Duration.between(attempt.getStartedAt(), attempt.getEndsAt())).isEqualTo(Duration.ofMinutes(60));
        assertThat(tagLevelRepository.findByMemberIdAndTagId(memberId, 3L).orElseThrow().getLevel())
            .isEqualTo(TagLevel.LV0);
        DiagnosisDetail detail = diagnosisService.getDetail(memberId, attempt.getId());
        assertThat(detail.problems()).extracting("difficulty").containsExactly(1, 2, 3);
        assertThat(detail.problems()).extracting("problemRevisionId").containsExactly(1001L, 1040L, 1092L);

        TagLevelSummary summary = tagLevelService.getTagLevels(memberId).get(0);
        assertThat(summary.inProgressDiagnosisId()).isEqualTo(attempt.getId());
        assertThat(summary.diagnosisEligible()).isFalse();

        assertError(() -> diagnosisService.start(memberId, 3L), MemberErrorCode.DIAGNOSIS_ALREADY_IN_PROGRESS);
    }

    @Test
    @DisplayName("없는 태그·문항 부족·LV3·쿨타임·정지 회원은 각 오류이고 응시와 태그 레벨을 만들지 않는다")
    void rejectsUnstartable() {
        Long memberId = member(9002L, MemberStatus.ACTIVE);
        given(problemTagClient.exists(99L)).willReturn(false);
        assertError(() -> diagnosisService.start(memberId, 99L), MemberErrorCode.LEVEL_TAG_NOT_FOUND);

        given(problemTagClient.exists(4L)).willReturn(true);
        given(problemDiagnosisClient.candidates(4L))
            .willReturn(Arrays.asList(CANDIDATES.get(0), null, CANDIDATES.get(2)));
        assertError(() -> diagnosisService.start(memberId, 4L), MemberErrorCode.DIAGNOSIS_PROBLEMS_UNAVAILABLE);
        assertThat(tagLevelRepository.findByMemberIdAndTagId(memberId, 4L)).isEmpty();

        MemberTagLevel lv3
            = MemberTagLevel.enterWithoutDiagnosis(memberId, 5L, LocalDateTime.now(ClockConfiguration.SERVICE_ZONE));
        ReflectionTestUtils.setField(lv3, "level", TagLevel.LV3);
        tagLevelRepository.save(lv3);
        assertError(() -> diagnosisService.start(memberId, 5L), MemberErrorCode.DIAGNOSIS_NOT_ELIGIBLE);

        MemberTagLevel cooling
            = MemberTagLevel.enterWithoutDiagnosis(memberId, 6L, LocalDateTime.now(ClockConfiguration.SERVICE_ZONE));
        ReflectionTestUtils
            .setField(cooling, "lastDiagnosisEndedAt", LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusDays(1));
        tagLevelRepository.save(cooling);
        assertThatThrownBy(() -> diagnosisService.start(memberId, 6L))
            .isInstanceOf(DiagnosisCooldownException.class)
            .satisfies(
                exception -> assertThat(((DiagnosisCooldownException)exception).getRetryAfterSeconds())
                    .isBetween(Duration.ofDays(2).toSeconds() - 60, Duration.ofDays(2).toSeconds())
            );

        Long suspended = member(9003L, MemberStatus.SUSPENDED);
        given(problemTagClient.exists(3L)).willReturn(true);
        given(problemDiagnosisClient.candidates(3L)).willReturn(CANDIDATES);
        assertError(() -> diagnosisService.start(suspended, 3L), MemberErrorCode.MEMBER_SUSPENDED);

        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select count(*) from diagnosis_attempts where member_id in (?, ?)",
                    Long.class,
                    memberId,
                    suspended
                )
        ).isZero();
    }

    @Test
    @DisplayName("같은 태그의 진단을 동시에 시작해도 한 건만 남고 나머지는 DIAGNOSIS_ALREADY_IN_PROGRESS")
    void concurrentStartKeepsOne() throws Exception {
        Long memberId = member(9004L, MemberStatus.ACTIVE);
        given(problemTagClient.exists(7L)).willReturn(true);
        given(problemDiagnosisClient.candidates(7L)).willReturn(CANDIDATES);
        int requests = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tasks.add(() -> {
                start.await();
                return diagnosisService.start(memberId, 7L);
            });
        }

        int succeeded = 0;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Object>> results = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Object> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode")
                        .isEqualTo(MemberErrorCode.DIAGNOSIS_ALREADY_IN_PROGRESS);
                }
            }
        } finally {
            executor.shutdown();
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(
            jdbcTemplate
                .queryForObject("select count(*) from diagnosis_attempts where member_id = ?", Long.class, memberId)
        ).isEqualTo(1L);
    }

    @Test
    @DisplayName(
        "Draft는 문항의 모든 언어 중 더 큰 seq만 반영하고(같은 seq 재전송 포함), 복구는 문항별 가장 큰 seq를 주며, 다른 회원·다른 문항·64KB 초과·제한 시각 이후는 거절한다"
    )
    void savesDrafts() {
        Long memberId = member(9005L, MemberStatus.ACTIVE);
        Long other = member(9006L, MemberStatus.ACTIVE);
        given(problemTagClient.exists(8L)).willReturn(true);
        given(problemDiagnosisClient.candidates(8L)).willReturn(CANDIDATES);
        Long diagnosisId = diagnosisService.start(memberId, 8L).attempt().getId();

        SaveResult first = diagnosisService.saveDraft(memberId, diagnosisId, 301L, "python3", "v5", 5);
        SaveResult stale = diagnosisService.saveDraft(memberId, diagnosisId, 301L, "python3", "v3", 3);
        SaveResult sameSeq = diagnosisService.saveDraft(memberId, diagnosisId, 301L, "python3", "other", 5);
        diagnosisService.saveDraft(memberId, diagnosisId, 301L, "java", "v6", 6);
        // 순번은 문항 단위라, 다른 언어로 늦게 온 이전 순번도 반영하지 않는다.
        SaveResult lateOtherLanguage = diagnosisService.saveDraft(memberId, diagnosisId, 301L, "python3", "v5b", 5);

        assertThat(first.applied()).isTrue();
        assertThat(first.draft().seq()).isEqualTo(5L);
        assertThat(stale.applied()).isFalse();
        assertThat(stale.draft().seq()).isEqualTo(5L);
        assertThat(stale.draft().sourceCode()).isEqualTo("v5");
        assertThat(sameSeq.applied()).isFalse();
        assertThat(sameSeq.draft().sourceCode()).isEqualTo("v5");
        assertThat(lateOtherLanguage.applied()).isFalse();
        assertThat(lateOtherLanguage.draft().language()).isEqualTo("java");
        assertThat(lateOtherLanguage.draft().seq()).isEqualTo(6L);
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "select source_code from diagnosis_drafts where diagnosis_attempt_id = ? and language = 'python3'",
                    String.class,
                    diagnosisId
                )
        ).isEqualTo("v5");
        Draft latest = diagnosisService.getDetail(memberId, diagnosisId).drafts().get(301L);
        assertThat(latest.language()).isEqualTo("java");
        assertThat(latest.seq()).isEqualTo(6L);

        assertError(
            () -> diagnosisService.saveDraft(other, diagnosisId, 301L, "java", "x", 7),
            MemberErrorCode.DIAGNOSIS_NOT_FOUND
        );
        assertError(
            () -> diagnosisService.saveDraft(memberId, diagnosisId, 999L, "java", "x", 7),
            MemberErrorCode.DIAGNOSIS_NOT_FOUND
        );
        assertError(
            () -> diagnosisService.saveDraft(memberId, diagnosisId, 301L, "java", "가".repeat(22_000), 7),
            MemberErrorCode.CODE_TOO_LARGE
        );

        jdbcTemplate
            .update(
                "update diagnosis_attempts set ends_at = ? where id = ?",
                LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusSeconds(1),
                diagnosisId
            );
        assertError(
            () -> diagnosisService.saveDraft(memberId, diagnosisId, 301L, "java", "late", 8),
            MemberErrorCode.DIAGNOSIS_CLOSED
        );
    }

    @Test
    @DisplayName("이력은 본인 응시만 태그로 걸러 최신 시작순으로 준다")
    void historyFiltersByTag() {
        Long memberId = member(9007L, MemberStatus.ACTIVE);
        Long other = member(9008L, MemberStatus.ACTIVE);
        given(problemTagClient.exists(10L)).willReturn(true);
        given(problemTagClient.exists(11L)).willReturn(true);
        given(problemDiagnosisClient.candidates(10L)).willReturn(CANDIDATES);
        given(problemDiagnosisClient.candidates(11L)).willReturn(CANDIDATES);
        Long first = diagnosisService.start(memberId, 10L).attempt().getId();
        Long second = diagnosisService.start(memberId, 11L).attempt().getId();
        diagnosisService.start(other, 10L);
        diagnosisService.start(other, 11L);
        PageRequest newestFirst = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "startedAt", "id"));

        assertThat(diagnosisService.getHistory(memberId, null, newestFirst).getContent())
            .extracting(DiagnosisAttempt::getId)
            .containsExactly(second, first);
        assertThat(diagnosisService.getHistory(memberId, 10L, newestFirst).getContent())
            .extracting(DiagnosisAttempt::getId)
            .containsExactly(first);
    }

    private void assertError(ThrowingCallable call, Object errorCode) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(errorCode);
    }

    private Long member(Long githubId, MemberStatus status) {
        Member member = Member.registerWithGitHub(githubId, "dg-" + githubId, null, null);
        ReflectionTestUtils.setField(member, "status", status);
        return memberRepository.saveAndFlush(member).getId();
    }
}
