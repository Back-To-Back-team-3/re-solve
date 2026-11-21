package com.backtoback.study.replica.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

/**
 * 학습 프로필 복제본 조건부 교체 쿼리 통합 테스트 (event-contract.md §M4·§1.5, ERD 4.5.10).
 * 순서 값 비교가 SQL 안에 있어 실제 MySQL로 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class MemberLearningProfileReplicaRepositoryTest {

    private static final Long MEMBER_ID = 7L;
    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 11, 21, 10, 0, 0, 123_456_000);
    private static final LocalDateTime LATER_SYNCED_AT = SYNCED_AT.plusMinutes(5);

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
    private MemberLearningProfileReplicaRepository repository;

    @Test
    @DisplayName("회원 ID 청크 조회는 기준 ID 다음부터 오름차순으로 개수만큼 반환한다")
    void findMemberIdsAfter_returnsAscendingIdsAfterCursor() {
        // given
        for (long memberId : new long[] {
            9L,
            3L,
            5L
        }) {
            repository.upsertFromLearningProfileUpdated(memberId, "EMPLOYMENT", null, null, null, 1L, SYNCED_AT);
        }

        // when & then
        assertThat(repository.findMemberIdsAfter(0L, Limit.of(10))).containsExactly(3L, 5L, 9L);
        assertThat(repository.findMemberIdsAfter(3L, Limit.of(1))).containsExactly(5L);
        assertThat(repository.findMemberIdsAfter(9L, Limit.of(10))).isEmpty();
    }

    @Test
    @DisplayName("복제본이 없는 회원의 학습 프로필 이벤트를 반영하면 복제본을 새로 만든다")
    void upsert_noReplica_inserts() {
        // when
        repository
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "EMPLOYMENT",
                "{\"3\": \"LV2\"}",
                "[\"java\"]",
                "[\"EVENING\"]",
                1L,
                SYNCED_AT
            );

        // then
        MemberLearningProfileReplica replica = findReplica();
        assertThat(replica.getGoal()).isEqualTo("EMPLOYMENT");
        assertThat(replica.getTagLevelsJson()).contains("\"3\"").contains("LV2");
        assertThat(replica.getPreferredLanguagesJson()).contains("java");
        assertThat(replica.getActiveTimeSlotsJson()).contains("EVENING");
        assertThat(replica.getProfileVersion()).isEqualTo(1L);
        assertThat(replica.getSyncedAt()).isEqualTo(SYNCED_AT);
    }

    @Test
    @DisplayName("저장된 버전보다 큰 학습 프로필 이벤트를 반영하면 모든 항목을 새 스냅샷으로 교체한다")
    void upsert_higherVersion_replacesAll() {
        // given
        repository
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "EMPLOYMENT",
                "{\"3\": \"LV2\"}",
                "[\"java\"]",
                "[\"EVENING\"]",
                1L,
                SYNCED_AT
            );

        // when
        repository.upsertFromLearningProfileUpdated(MEMBER_ID, null, null, "[\"python3\"]", null, 2L, LATER_SYNCED_AT);

        // then
        MemberLearningProfileReplica replica = findReplica();
        assertThat(replica.getGoal()).isNull();
        assertThat(replica.getTagLevelsJson()).isNull();
        assertThat(replica.getPreferredLanguagesJson()).contains("python3");
        assertThat(replica.getActiveTimeSlotsJson()).isNull();
        assertThat(replica.getProfileVersion()).isEqualTo(2L);
        assertThat(replica.getSyncedAt()).isEqualTo(LATER_SYNCED_AT);
    }

    @Test
    @DisplayName("저장된 버전과 같은 학습 프로필 이벤트를 반영하면 복제본을 바꾸지 않는다")
    void upsert_sameVersion_ignored() {
        // given
        repository
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "EMPLOYMENT",
                "{\"3\": \"LV2\"}",
                "[\"java\"]",
                "[\"EVENING\"]",
                2L,
                SYNCED_AT
            );

        // when
        repository
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "CONTEST",
                "{\"3\": \"LV3\"}",
                "[\"cpp\"]",
                "[\"NIGHT\"]",
                2L,
                LATER_SYNCED_AT
            );

        // then
        MemberLearningProfileReplica replica = findReplica();
        assertThat(replica.getGoal()).isEqualTo("EMPLOYMENT");
        assertThat(replica.getPreferredLanguagesJson()).contains("java");
        assertThat(replica.getProfileVersion()).isEqualTo(2L);
        assertThat(replica.getSyncedAt()).isEqualTo(SYNCED_AT);
    }

    @Test
    @DisplayName("저장된 버전보다 낮은 지연 이벤트를 반영하면 복제본을 바꾸지 않는다")
    void upsert_lowerVersion_ignored() {
        // given
        repository
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "STUDY",
                "{\"5\": \"LV1\"}",
                "[\"java\"]",
                "[\"MORNING\"]",
                3L,
                SYNCED_AT
            );

        // when
        repository
            .upsertFromLearningProfileUpdated(
                MEMBER_ID,
                "EMPLOYMENT",
                "{\"5\": \"LV0\"}",
                "[\"cpp\"]",
                "[\"NIGHT\"]",
                2L,
                LATER_SYNCED_AT
            );

        // then
        MemberLearningProfileReplica replica = findReplica();
        assertThat(replica.getGoal()).isEqualTo("STUDY");
        assertThat(replica.getTagLevelsJson()).contains("LV1");
        assertThat(replica.getProfileVersion()).isEqualTo(3L);
        assertThat(replica.getSyncedAt()).isEqualTo(SYNCED_AT);
    }

    private MemberLearningProfileReplica findReplica() {
        List<MemberLearningProfileReplica> replicas = repository.findAllByMemberIdIn(List.of(MEMBER_ID));
        assertThat(replicas).hasSize(1);
        return replicas.get(0);
    }
}
