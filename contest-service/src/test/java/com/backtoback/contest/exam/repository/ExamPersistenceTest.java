package com.backtoback.contest.exam.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamProblem;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.global.config.JpaConfig;

import jakarta.persistence.EntityManager;

/**
 * Flyway로 만든 MySQL 스키마에서 생성 컬럼과 시험별 유니크·FK 계약을 검증한다.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(JpaConfig.class)
@Testcontainers
class ExamPersistenceTest {

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final LocalDateTime ENDS_AT = STARTS_AT.plusMinutes(90);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36").withDatabaseName("contest_test");

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private ExamProblemRepository examProblemRepository;

    @Autowired
    private ExamParticipantRepository examParticipantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Test
    @DisplayName("시험·고정 회차·참가자를 명세의 타입과 초기 값으로 저장한다")
    void savesExamGraphWithGeneratedParticipantKey() {
        Exam exam = saveExam();
        ExamProblem problem = examProblemRepository.saveAndFlush(createProblem(exam));
        ExamParticipant participant = examParticipantRepository.saveAndFlush(createParticipant(exam));

        // INSERT에서 생성 컬럼을 직접 쓰지 않고 DB가 만든 값을 다시 가져와야 한다.
        assertThat(participant.getActiveMemberId()).isEqualTo(3L);
        entityManager.clear();

        Exam storedExam = examRepository.findById(exam.getId()).orElseThrow();
        ExamProblem storedProblem = examProblemRepository.findById(problem.getId()).orElseThrow();
        ExamParticipant storedParticipant = examParticipantRepository.findById(participant.getId()).orElseThrow();

        assertThat(storedExam.getStudyId()).isEqualTo(1L);
        assertThat(storedExam.getCreatorId()).isEqualTo(2L);
        assertThat(storedExam.getMode()).isEqualTo(ExamMode.FIXED);
        assertThat(storedExam.getDurationMinutes()).isNull();
        assertThat(storedExam.getStatus()).isEqualTo(ExamStatus.SCHEDULED);
        assertThat(storedExam.getExamRevision()).isEqualTo(1);
        assertThat(storedExam.getResultRevision()).isZero();
        assertThat(storedExam.getVersion()).isZero();
        assertThat(storedExam.getStartsAt()).isEqualTo(STARTS_AT);
        assertThat(storedExam.getEndsAt()).isEqualTo(ENDS_AT);
        assertThat(storedExam.getTotalScore()).isEqualByComparingTo("100.00");
        assertThat(storedExam.getCreatedAt()).isNotNull();
        assertThat(storedExam.getUpdatedAt()).isNotNull();
        assertThat(storedProblem.getExam().getId()).isEqualTo(exam.getId());
        assertThat(storedProblem.getProblemRevisionId()).isEqualTo(1001L);
        assertThat(storedProblem.getProblemTitle()).isEqualTo("고정 회차 문제");
        assertThat(storedProblem.getScore()).isEqualByComparingTo("33.33");
        assertThat(storedParticipant.getStatus()).isEqualTo(ExamParticipantStatus.REGISTERED);
        assertThat(storedParticipant.getParticipantRevision()).isEqualTo(1L);
        assertThat(storedParticipant.getRegisteredAt()).isEqualTo(STARTS_AT.minusHours(1));
        assertThat(storedParticipant.getPersonalEndsAt()).isEqualTo(ENDS_AT);
        assertThat(storedParticipant.getEnteredAt()).isNull();
        assertThat(storedParticipant.getTotalScore()).isEqualByComparingTo("0.00");
        assertThat(storedParticipant.isRanked()).isFalse();
        assertThat(storedParticipant.getFinalRank()).isNull();
        assertThat(storedParticipant.getVersion()).isZero();
    }

    @Test
    @DisplayName("같은 시험에 같은 문제를 두 번 편입할 수 없다")
    void rejectsDuplicateProblemWithinExam() {
        Exam exam = saveExam();
        examProblemRepository.saveAndFlush(createProblem(exam));

        assertThatThrownBy(() -> examProblemRepository.saveAndFlush(createProblem(exam)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 시험에 같은 회원의 취소되지 않은 참가를 두 번 저장할 수 없다")
    void rejectsDuplicateActiveParticipantWithinExam() {
        Exam exam = saveExam();
        examParticipantRepository.saveAndFlush(createParticipant(exam));

        assertThatThrownBy(() -> examParticipantRepository.saveAndFlush(createParticipant(exam)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("취소된 참가 이력을 보존하면서 같은 시험에 새 참가를 저장할 수 있다")
    void allowsReRegistrationAfterMultipleCancellations() {
        Exam exam = saveExam();
        ExamParticipant first = examParticipantRepository.saveAndFlush(createParticipant(exam));
        cancelStoredParticipant(first.getId());
        ExamParticipant second = examParticipantRepository.saveAndFlush(createParticipant(exam));
        cancelStoredParticipant(second.getId());
        ExamParticipant current = examParticipantRepository.saveAndFlush(createParticipant(exam));
        entityManager.clear();

        ExamParticipant storedFirst = examParticipantRepository.findById(first.getId()).orElseThrow();
        ExamParticipant storedSecond = examParticipantRepository.findById(second.getId()).orElseThrow();
        ExamParticipant storedCurrent = examParticipantRepository.findById(current.getId()).orElseThrow();

        assertThat(storedFirst.getStatus()).isEqualTo(ExamParticipantStatus.CANCELED);
        assertThat(storedFirst.getActiveMemberId()).isNull();
        assertThat(storedSecond.getActiveMemberId()).isNull();
        assertThat(storedCurrent.getActiveMemberId()).isEqualTo(3L);
        assertThat(storedCurrent.getId()).isNotEqualTo(first.getId()).isNotEqualTo(second.getId());
        assertThat(examParticipantRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 시험에는 같은 문제와 같은 회원을 저장할 수 있다")
    void scopesProblemAndParticipantUniquenessToExam() {
        Exam firstExam = saveExam();
        Exam secondExam = saveExam();

        examProblemRepository.saveAndFlush(createProblem(firstExam));
        examProblemRepository.saveAndFlush(createProblem(secondExam));
        examParticipantRepository.saveAndFlush(createParticipant(firstExam));
        examParticipantRepository.saveAndFlush(createParticipant(secondExam));

        assertThat(examProblemRepository.count()).isEqualTo(2);
        assertThat(examParticipantRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("고정 문제를 가진 시험의 물리 삭제는 FK로 차단한다")
    void rejectsDeletingExamWithProblem() {
        Exam exam = saveExam();
        examProblemRepository.saveAndFlush(createProblem(exam));

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM exams WHERE id = ?", exam.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("참가자를 가진 시험의 물리 삭제는 FK로 차단한다")
    void rejectsDeletingExamWithParticipant() {
        Exam exam = saveExam();
        examParticipantRepository.saveAndFlush(createParticipant(exam));

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM exams WHERE id = ?", exam.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Exam saveExam() {
        return examRepository
            .saveAndFlush(
                Exam.create(1L, 2L, "모의 코테", ExamMode.FIXED, STARTS_AT, ENDS_AT, null, new BigDecimal("100.00"))
            );
    }

    private ExamProblem createProblem(Exam exam) {
        return ExamProblem.create(exam, 301L, 1001L, "고정 회차 문제", 1, new BigDecimal("33.33"), 1);
    }

    private ExamParticipant createParticipant(Exam exam) {
        return ExamParticipant.create(exam, 3L, STARTS_AT.minusHours(1), ENDS_AT);
    }

    private void cancelStoredParticipant(Long participantId) {
        // 서비스 상태 전이를 구현하지 않고 DB 생성 컬럼의 NULL 계산과 유니크 제약만 검증한다.
        jdbcTemplate.update("UPDATE exam_participants SET status = 'CANCELED' WHERE id = ?", participantId);
    }
}
