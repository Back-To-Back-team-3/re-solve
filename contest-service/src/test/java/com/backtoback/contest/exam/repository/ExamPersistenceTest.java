package com.backtoback.contest.exam.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
 * <p>Testcontainers가 테스트 전용 MySQL을 시작하고 Flyway가 테이블을 만든 뒤 JPA 매핑을 검증한다.
 * 각 테스트는 DataJpaTest의 트랜잭션에서 실행하고 종료 시 롤백한다.
 * saveAndFlush로 SQL을 실제 실행하며 재조회 전 영속성 컨텍스트를 비워 메모리 객체만 검증하는 것을 피한다.
 * 참가 자격·서비스 상태 전이·이력·Outbox·결과 집계의 동작은 이 저장 계약 테스트에 포함하지 않는다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.1 시험 (exams): 초기값과 컬럼 타입</li>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.2 시험 문제 (exam_problems): 시험별 문제 유일 제약과 내부 FK</li>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.3 시험 참가자 (exam_participants): 생성 컬럼·참가 유일 제약·취소 행 보존</li>
 * </ul>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(JpaConfig.class)
@Testcontainers
class ExamPersistenceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long CREATOR_ID = 2L;
    private static final Long MEMBER_ID = 3L;
    private static final Long PROBLEM_ID = 301L;
    private static final Long PROBLEM_REVISION_ID = 1001L;
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

    /**
     * 컨테이너가 실제 할당한 접속 정보를 테스트의 DataSource에 연결한다.
     * 개발용 DB 주소를 사용하지 않으므로 Compose 서비스 실행과 분리된다.
     *
     * @param registry 테스트 컨텍스트의 동적 설정 등록 대상
     */
    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * 세 저장 모델의 기본 타입·시간·점수·초기 상태와 감사 시간이 DB 왕복 후에도 유지되는지 확인한다.
     * 생성 컬럼은 저장 직후 객체에도 반영돼야 하며 직접 INSERT하지 않아야 한다.
     */
    @Test
    @DisplayName("시험·고정 회차·참가자를 명세의 타입과 초기 값으로 저장한다")
    void savesExamGraphWithGeneratedParticipantKey() {
        // given
        Exam exam = saveExam();
        ExamProblem problem = createProblem(exam);
        ExamParticipant participant = createParticipant(exam);

        // when
        examProblemRepository.saveAndFlush(problem);
        examParticipantRepository.saveAndFlush(participant);
        Long generatedActiveMemberId = participant.getActiveMemberId();
        // 1차 캐시를 비워 아래 조회가 기존 객체가 아닌 DB 저장값을 확인하게 한다.
        entityManager.clear();

        Exam storedExam = examRepository.findById(exam.getId()).orElseThrow();
        ExamProblem storedProblem = examProblemRepository.findById(problem.getId()).orElseThrow();
        ExamParticipant storedParticipant = examParticipantRepository.findById(participant.getId()).orElseThrow();

        // then
        assertThat(generatedActiveMemberId).isEqualTo(MEMBER_ID);
        assertThat(storedExam.getStudyId()).isEqualTo(STUDY_ID);
        assertThat(storedExam.getCreatorId()).isEqualTo(CREATOR_ID);
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
        assertThat(storedProblem.getProblemRevisionId()).isEqualTo(PROBLEM_REVISION_ID);
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

    /**
     * 같은 문제 회차 객체를 새로 만들어도 시험·문제 조합의 중복은 DB에서 차단되는지 확인한다.
     * flush 시 발생하는 제약 위반이 Spring 데이터 접근 예외로 전달되는지 검증한다.
     */
    @Test
    @DisplayName("같은 시험에 같은 문제를 두 번 편입할 수 없다")
    void rejectsDuplicateProblemWithinExam() {
        // given
        Exam exam = saveExam();
        examProblemRepository.saveAndFlush(createProblem(exam));

        // when & then
        assertThatThrownBy(() -> examProblemRepository.saveAndFlush(createProblem(exam)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 취소되지 않은 참가의 생성 컬럼이 같은 회원 ID로 계산되어 중복 등록을 막는지 확인한다.
     */
    @Test
    @DisplayName("같은 시험에 같은 회원의 취소되지 않은 참가를 두 번 저장할 수 없다")
    void rejectsDuplicateActiveParticipantWithinExam() {
        // given
        Exam exam = saveExam();
        examParticipantRepository.saveAndFlush(createParticipant(exam));

        // when & then
        assertThatThrownBy(() -> examParticipantRepository.saveAndFlush(createParticipant(exam)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 취소 행 두 건의 생성 컬럼이 null로 남아 새 참가를 막지 않으며 기존 행도 보존되는지 확인한다.
     * 직접 SQL 취소는 DB 제약 검증을 위한 준비이며 서비스 상태 전이를 검증하는 동작이 아니다.
     */
    @Test
    @DisplayName("취소된 참가 이력을 보존하면서 같은 시험에 새 참가를 저장할 수 있다")
    void allowsReRegistrationAfterMultipleCancellations() {
        // given - 같은 회원의 취소 이력을 두 건 준비한다.
        Exam exam = saveExam();
        ExamParticipant firstCanceledParticipant = examParticipantRepository.saveAndFlush(createParticipant(exam));
        cancelStoredParticipant(firstCanceledParticipant.getId());
        ExamParticipant secondCanceledParticipant = examParticipantRepository.saveAndFlush(createParticipant(exam));
        cancelStoredParticipant(secondCanceledParticipant.getId());

        // when - 취소 이력을 삭제하지 않고 같은 회원의 새 참가를 저장한다.
        ExamParticipant activeParticipant = examParticipantRepository.saveAndFlush(createParticipant(exam));
        entityManager.clear();

        ExamParticipant storedFirst
            = examParticipantRepository.findById(firstCanceledParticipant.getId()).orElseThrow();
        ExamParticipant storedSecond
            = examParticipantRepository.findById(secondCanceledParticipant.getId()).orElseThrow();
        ExamParticipant storedCurrent = examParticipantRepository.findById(activeParticipant.getId()).orElseThrow();

        // then
        assertThat(storedFirst.getStatus()).isEqualTo(ExamParticipantStatus.CANCELED);
        assertThat(storedFirst.getActiveMemberId()).isNull();
        assertThat(storedSecond.getActiveMemberId()).isNull();
        assertThat(storedCurrent.getActiveMemberId()).isEqualTo(MEMBER_ID);
        assertThat(storedCurrent.getId())
            .isNotEqualTo(firstCanceledParticipant.getId())
            .isNotEqualTo(secondCanceledParticipant.getId());
        assertThat(examParticipantRepository.count()).isEqualTo(3);
    }

    /**
     * 문제·회원 ID 자체가 아니라 시험 ID와의 조합에 유일 제약이 적용되는지 확인한다.
     */
    @Test
    @DisplayName("다른 시험에는 같은 문제와 같은 회원을 저장할 수 있다")
    void scopesProblemAndParticipantUniquenessToExam() {
        // given
        List<Exam> exams = saveExams(2);
        Exam firstExam = exams.get(0);
        Exam secondExam = exams.get(1);

        // when - 시험이 달라도 동일한 문제·회원 ID를 사용한다.
        examProblemRepository.saveAndFlush(createProblem(firstExam));
        examProblemRepository.saveAndFlush(createProblem(secondExam));
        examParticipantRepository.saveAndFlush(createParticipant(firstExam));
        examParticipantRepository.saveAndFlush(createParticipant(secondExam));

        // then
        assertThat(examProblemRepository.count()).isEqualTo(2);
        assertThat(examParticipantRepository.count()).isEqualTo(2);
    }

    /**
     * 하위 문제가 남아 있을 때 DB의 시험 FK가 부모 행의 DELETE를 거절하는지 확인한다.
     * ORM cascade 동작과 혼동하지 않도록 직접 SQL 삭제로 제약을 검증한다.
     */
    @Test
    @DisplayName("고정 문제를 가진 시험의 물리 삭제는 FK로 차단한다")
    void rejectsDeletingExamWithProblem() {
        // given
        Exam exam = saveExam();
        examProblemRepository.saveAndFlush(createProblem(exam));

        // when & then
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM exams WHERE id = ?", exam.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 참가 기록이 남아 있을 때 시험의 물리 삭제가 DB 내부 FK로 차단되는지 확인한다.
     */
    @Test
    @DisplayName("참가자를 가진 시험의 물리 삭제는 FK로 차단한다")
    void rejectsDeletingExamWithParticipant() {
        // given
        Exam exam = saveExam();
        examParticipantRepository.saveAndFlush(createParticipant(exam));

        // when & then
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM exams WHERE id = ?", exam.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 공통 기본 제목으로 시험을 저장하고 flush하여 하위 행이 참조할 ID를 준비한다.
     *
     * @return DB에 저장된 FIXED 시험
     */
    private Exam saveExam() {
        return saveExam("모의 코테");
    }

    /**
     * 제목만 다른 시나리오도 같은 시험 생성·저장 로직을 사용하게 한다.
     *
     * @param title 저장할 시험 제목
     * @return 기본 스터디·개설자·시간·배점을 가진 저장된 FIXED 시험
     */
    private Exam saveExam(String title) {
        Exam exam
            = Exam
                .create(
                    STUDY_ID,
                    CREATOR_ID,
                    title,
                    ExamMode.FIXED,
                    STARTS_AT,
                    ENDS_AT,
                    null,
                    new BigDecimal("100.00")
                );
        return examRepository.saveAndFlush(exam);
    }

    /**
     * 요청한 개수만큼 시험을 각각 저장한다. 제목에 순번을 붙여 준비된 시험을 구분한다.
     *
     * @param count 준비할 시험 개수
     * @return 서로 다른 DB 식별자를 가진 시험 목록, 0 이하이면 빈 목록
     */
    private List<Exam> saveExams(int count) {
        List<Exam> exams = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            exams.add(saveExam("모의 코테 " + (index + 1)));
        }
        return exams;
    }

    /**
     * 저장된 시험에 편입할 기본 문제의 객체만 만든다. DB에는 저장하지 않는다.
     *
     * @param exam 저장된 소속 시험
     * @return 기본 문제 ID·고정 회차·배점·표시 순서를 가진 미저장 객체
     */
    private ExamProblem createProblem(Exam exam) {
        return ExamProblem.create(exam, PROBLEM_ID, PROBLEM_REVISION_ID, "고정 회차 문제", 1, new BigDecimal("33.33"), 1);
    }

    /**
     * 같은 회원이 시험별로 참가하는 시나리오의 객체를 만든다. 저장·입장 처리는 수행하지 않는다.
     *
     * @param exam 저장된 소속 시험
     * @return 시험 종료 시간을 개인 마감으로 가진 기본 회원의 미저장 참가 객체
     */
    private ExamParticipant createParticipant(Exam exam) {
        return ExamParticipant.create(exam, MEMBER_ID, STARTS_AT.minusHours(1), ENDS_AT);
    }

    /**
     * 생성 컬럼·유일 제약을 검증하기 위해 저장된 참가의 상태만 직접 SQL로 바꾼다.
     * JPA 감사·낙관적 락·상태 순번·이력·Outbox 처리는 수행하지 않으며 서비스 구현으로 사용하지 않는다.
     * 이후 DB 저장값을 확인하려면 영속성 컨텍스트를 비우고 재조회해야 한다.
     *
     * @param participantId 취소 상태로 준비할 저장된 참가 ID
     */
    private void cancelStoredParticipant(Long participantId) {
        jdbcTemplate.update("UPDATE exam_participants SET status = 'CANCELED' WHERE id = ?", participantId);
    }
}
