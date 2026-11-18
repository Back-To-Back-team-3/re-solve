package com.backtoback.study.hint.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.service.HintCommandService;

/**
 * 힌트 요청 회수 쿼리 통합 테스트 (scheduler §6.1).
 * 대상 조회의 기준 시각 경계·COALESCE 판정, 생성 시작 시각 기록, 일일 횟수 반환 하한을 실제 MySQL로 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class HintRecoveryQueryIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 18, 13, 59);
    private static final List<HintRequestStatus> IN_PROGRESS
        = List.of(HintRequestStatus.REQUESTED, HintRequestStatus.GENERATING);

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private HintRequestRepository hintRequestRepository;

    @Autowired
    private HintDailyUsageRepository hintDailyUsageRepository;

    @Autowired
    private HintCommandService hintCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("기준 시각이 정확히 1분 전인 요청은 회수 대상이고 59초 전인 요청은 대상이 아니다")
    void findRecoveryTargets_boundary_includesExactCutoffOnly() {
        Long atCutoff = insertRequest(1L, "REQUESTED", CUTOFF, null);
        insertRequest(2L, "REQUESTED", CUTOFF.plusSeconds(1), null);

        assertThat(targetIds()).containsExactly(atCutoff);
    }

    @Test
    @DisplayName("생성 시작 시각이 없는 GENERATING 요청은 요청 시각으로 판단해 회수 대상에 포함한다")
    void findRecoveryTargets_generatingWithoutStartedAt_usesCreatedAt() {
        Long legacyGenerating = insertRequest(1L, "GENERATING", CUTOFF.minusDays(1), null);

        assertThat(targetIds()).containsExactly(legacyGenerating);
    }

    @Test
    @DisplayName("GENERATING 요청은 요청 시각이 오래돼도 생성 시작이 1분 안이면 회수 대상이 아니다")
    void findRecoveryTargets_recentlyStartedGenerating_excluded() {
        Long stale = insertRequest(1L, "GENERATING", CUTOFF.minusMinutes(5), CUTOFF.minusMinutes(2));
        insertRequest(2L, "GENERATING", CUTOFF.minusMinutes(5), CUTOFF.plusSeconds(30));

        assertThat(targetIds()).containsExactly(stale);
    }

    @Test
    @DisplayName("종결 상태 요청은 오래돼도 회수 대상이 아니며 대상은 id 순으로 반환한다")
    void findRecoveryTargets_terminalStatusesExcluded_orderedById() {
        Long first = insertRequest(1L, "REQUESTED", CUTOFF.minusMinutes(1), null);
        insertRequest(2L, "READY", CUTOFF.minusDays(1), null);
        insertRequest(3L, "FALLBACK", CUTOFF.minusDays(1), null);
        insertRequest(4L, "BLOCKED", CUTOFF.minusDays(1), null);
        Long second = insertRequest(5L, "GENERATING", CUTOFF.minusMinutes(3), CUTOFF.minusMinutes(2));

        assertThat(targetIds()).containsExactly(first, second);
    }

    @Test
    @DisplayName("생성 시작 전이 후 생성 시작 시각이 기록된다")
    void transitionToGenerating_recordsGenerationStartedAt() {
        Long id = insertRequest(1L, "REQUESTED", CUTOFF, null);

        hintCommandService.transitionToGenerating(id);

        Timestamp startedAt
            = jdbcTemplate
                .queryForObject("SELECT generation_started_at FROM hint_requests WHERE id = ?", Timestamp.class, id);
        assertThat(startedAt).isNotNull();
    }

    @Test
    @DisplayName("사용 횟수가 0이면 반환해도 0 미만이 되지 않는다")
    void decrementIfPositive_zero_staysZero() {
        LocalDate usageDate = LocalDate.of(2026, 11, 18);
        jdbcTemplate
            .update(
                "INSERT INTO hint_daily_usages (member_id, usage_date, used_count, created_at, updated_at)"
                    + " VALUES (?, ?, 0, NOW(6), NOW(6))",
                9L,
                usageDate
            );

        int updated = hintDailyUsageRepository.decrementIfPositive(9L, usageDate, CUTOFF);

        assertThat(updated).isZero();
        assertThat(
            jdbcTemplate
                .queryForObject(
                    "SELECT used_count FROM hint_daily_usages WHERE member_id = ? AND usage_date = ?",
                    Integer.class,
                    9L,
                    usageDate
                )
        ).isZero();
    }

    private List<Long> targetIds() {
        return hintRequestRepository
            .findRecoveryTargets(IN_PROGRESS, CUTOFF, PageRequest.of(0, 100))
            .stream()
            .map(HintRequest::getId)
            .toList();
    }

    private Long insertRequest(
        Long memberId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime generationStartedAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement
                = connection
                    .prepareStatement(
                        "INSERT INTO hint_requests (member_id, problem_id, problem_revision_id, level, status,"
                            + " is_cached, is_fallback, generation_started_at, created_at, updated_at)"
                            + " VALUES (?, 100, 1000, 1, ?, false, false, ?, ?, ?)",
                        new String[] {
                            "id"
            }
                    );
            statement.setLong(1, memberId);
            statement.setString(2, status);
            statement.setTimestamp(3, generationStartedAt == null ? null : Timestamp.valueOf(generationStartedAt));
            statement.setTimestamp(4, Timestamp.valueOf(createdAt));
            statement.setTimestamp(5, Timestamp.valueOf(createdAt));
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
