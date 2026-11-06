package com.backtoback.contest.exam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.contest.exam.domain.ExamStatus;

/** 실제 DB에서 시험별 권한과 페이지 필터를 확인한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ExamQueryRepositoryTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExamRepository exams;
    @Autowired
    private ExamParticipantRepository participants;

    /** 격리된 DB와 Redis 주소를 테스트 컨텍스트에 연결한다. */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** 시험과 참가 이력을 각 시나리오에 맞게 다시 준비한다. */
    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM exam_participant_histories");
        jdbc.update("DELETE FROM exam_participants");
        jdbc.update("DELETE FROM exam_problems");
        jdbc.update("DELETE FROM exams");
        for (int number = 1; number <= 3; number++) {
            jdbc
                .update(
                    "INSERT INTO exams (id,study_id,creator_id,title,mode,starts_at,ends_at,status,"
                        + "exam_revision,total_score,result_revision,version,created_at,updated_at) "
                        + "VALUES (?,12,7,?,'FIXED',DATE_ADD(NOW(6), INTERVAL ? HOUR),"
                        + "DATE_ADD(NOW(6), INTERVAL 5 HOUR),?,1,100,0,0,NOW(6),NOW(6))",
                    number,
                    "시험" + number,
                    number,
                    number == 3 ? "CANCELED" : "SCHEDULED"
                );
        }
        insertParticipant(1, "CANCELED");
        insertParticipant(2, "CANCELED");
        insertParticipant(2, "REGISTERED");
    }

    /** 취소와 재등록 이력을 서로 다른 참가 행으로 저장한다. */
    private void insertParticipant(long exam, String status) {
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,"
                    + "personal_ends_at,total_score,is_ranked,version,created_at,updated_at) "
                    + "VALUES (?,7,?,1,NOW(6),DATE_ADD(NOW(6), INTERVAL 5 HOUR),0,false,0,NOW(6),NOW(6))",
                exam,
                status
            );
    }

    /** 권한을 먼저 제한한 뒤 필터와 정렬 및 전체 건수를 계산하는지 확인한다. */
    @Test
    @DisplayName("탈퇴자의 취소 이력은 제외하고 재등록된 시험만 페이지에 포함한다")
    void visibleExamsExcludeCanceledHistory() {
        // given
        var page = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "startsAt"));
        // when
        var visible = exams.findVisible(12, 7, false, ExamStatus.SCHEDULED, page);
        // then
        assertThat(participants.hasActiveInStudy(12, 7)).isTrue();
        assertThat(visible.getTotalElements()).isEqualTo(1);
        assertThat(visible.getContent()).extracting("id").containsExactly(2L);
        assertThat(exams.findVisible(12, 7, true, null, page).getTotalElements()).isEqualTo(3);
        assertThat(exams.findVisible(12, 7, false, ExamStatus.CANCELED, page).getTotalElements()).isZero();
    }
}
