package com.backtoback.study.replica.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload;
import com.backtoback.study.replica.event.dto.LearningProfileUpdatedPayload.TagLevel;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

/**
 * 학습 프로필 변경 이벤트 중복 수신 통합 테스트 (event-contract.md §1.4 소비 규칙).
 * 소비 기록 저장소가 소비 트랜잭션에 참여하므로 mock이 아닌 실제 트랜잭션·MySQL로 커밋 결과를 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LearningProfileReplicaConsumerServiceIntegrationTest {

    private static final String EVENT_ID = "8b3e4d5c-6f70-4b82-8c9d-1e2f3a4b5c6d";
    private static final long MEMBER_ID = 9201L;

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
    private LearningProfileReplicaConsumerService service;

    @Autowired
    private MemberLearningProfileReplicaRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("이미 커밋된 학습 프로필 이벤트를 다시 받으면 예외 없이 커밋되고 복제본과 소비 기록은 한 번만 남는다")
    void consume_sameEventTwice_secondCommitsWithoutChange() {
        // given
        LearningProfileUpdatedPayload payload
            = new LearningProfileUpdatedPayload(
                EVENT_ID,
                "2026-11-21T10:00:00.123456+09:00",
                "corr-dup",
                String.valueOf(MEMBER_ID),
                1L,
                "EMPLOYMENT",
                List.of("java"),
                List.of("EVENING"),
                List.of(new TagLevel("3", "LV2"))
            );
        service.consume(payload);

        // when & then
        assertThatCode(() -> service.consume(payload)).doesNotThrowAnyException();
        List<MemberLearningProfileReplica> replicas = repository.findAllByMemberIdIn(List.of(MEMBER_ID));
        assertThat(replicas).hasSize(1);
        assertThat(replicas.get(0).getProfileVersion()).isEqualTo(1L);
        Integer processed
            = jdbcTemplate
                .queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, EVENT_ID);
        assertThat(processed).isEqualTo(1);
    }
}
