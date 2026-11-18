package com.backtoback.study.replica.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;

/**
 * 회원 복제본 조건부 반영 쿼리 통합 테스트 (event-contract.md §M1·§M2·§M3·§1.5, ERD 3.5).
 * 순서 값 비교가 SQL 안에 있어 실제 MySQL로 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class MemberReplicaRepositoryTest {

    private static final Long MEMBER_ID = 7L;
    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 11, 16, 10, 0, 0, 123_456_000);
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
    private MemberReplicaRepository repository;

    @Test
    @DisplayName("복제본이 없는 회원의 회원 정보 이벤트를 반영하면 복제본을 새로 만든다")
    void upsertFromMemberUpdated_noReplica_inserts() {
        // when
        repository.upsertFromMemberUpdated(MEMBER_ID, "닉네임", "https://img/1.png", "ACTIVE", 1L, SYNCED_AT);

        // then
        MemberReplica replica = repository.findByMemberId(MEMBER_ID).orElseThrow();
        assertThat(replica.getNickname()).isEqualTo("닉네임");
        assertThat(replica.getProfileImageUrl()).isEqualTo("https://img/1.png");
        assertThat(replica.getMemberStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(replica.getSourceVersion()).isEqualTo(1L);
        assertThat(replica.getSyncedAt()).isEqualTo(SYNCED_AT);
    }

    @Test
    @DisplayName("같은 회원의 더 큰 순서 값 이벤트를 반영하면 행 하나의 모든 필드를 갱신한다")
    void upsertFromMemberUpdated_newerVersion_updatesSingleRow() {
        // given
        repository.upsertFromMemberUpdated(MEMBER_ID, "이전 닉네임", "https://img/1.png", "ACTIVE", 1L, SYNCED_AT);

        // when
        repository.upsertFromMemberUpdated(MEMBER_ID, "새 닉네임", null, "SUSPENDED", 2L, LATER_SYNCED_AT);

        // then
        // source_version을 먼저 대입하면 뒤 필드의 조건이 거짓이 되어 닉네임이 바뀌지 않는다. 대입 순서를 함께 검증한다.
        List<MemberReplica> replicas = repository.findAllByMemberIdIn(List.of(MEMBER_ID));
        assertThat(replicas).hasSize(1);
        MemberReplica replica = replicas.get(0);
        assertThat(replica.getNickname()).isEqualTo("새 닉네임");
        assertThat(replica.getProfileImageUrl()).isNull();
        assertThat(replica.getMemberStatus()).isEqualTo(MemberStatus.SUSPENDED);
        assertThat(replica.getSourceVersion()).isEqualTo(2L);
        assertThat(replica.getSyncedAt()).isEqualTo(LATER_SYNCED_AT);
    }

    @Test
    @DisplayName("저장된 순서 값과 같거나 작은 회원 정보 이벤트는 복제본을 바꾸지 않는다")
    void upsertFromMemberUpdated_sameOrOlderVersion_ignored() {
        // given
        repository.upsertFromMemberUpdated(MEMBER_ID, "현재 닉네임", null, "ACTIVE", 3L, SYNCED_AT);

        // when
        repository.upsertFromMemberUpdated(MEMBER_ID, "같은 순서", null, "SUSPENDED", 3L, LATER_SYNCED_AT);
        repository.upsertFromMemberUpdated(MEMBER_ID, "지연 도착", null, "SUSPENDED", 2L, LATER_SYNCED_AT);

        // then
        MemberReplica replica = repository.findByMemberId(MEMBER_ID).orElseThrow();
        assertThat(replica.getNickname()).isEqualTo("현재 닉네임");
        assertThat(replica.getMemberStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(replica.getSourceVersion()).isEqualTo(3L);
        assertThat(replica.getSyncedAt()).isEqualTo(SYNCED_AT);
    }

    @Test
    @DisplayName("탈퇴한 회원의 복제본은 더 큰 순서 값의 회원 정보 이벤트로도 바꾸지 않는다")
    void upsertFromMemberUpdated_withdrawnReplica_ignored() {
        // given
        repository.upsertWithdrawn(MEMBER_ID, 5L, SYNCED_AT);

        // when
        repository.upsertFromMemberUpdated(MEMBER_ID, "되살아난 닉네임", null, "ACTIVE", 6L, LATER_SYNCED_AT);

        // then
        MemberReplica replica = repository.findByMemberId(MEMBER_ID).orElseThrow();
        assertThat(replica.getMemberStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(replica.getSourceVersion()).isEqualTo(5L);
        assertThat(replica.getNickname()).isEqualTo(MemberReplica.WITHDRAWN_NICKNAME);
        assertThat(replica.getProfileImageUrl()).isNull();
    }

    @Test
    @DisplayName("복제본이 없는 회원의 탈퇴를 반영하면 익명화한 탈퇴 복제본을 새로 만든다")
    void upsertWithdrawn_noReplica_insertsAnonymized() {
        // when
        repository.upsertWithdrawn(MEMBER_ID, 5L, SYNCED_AT);

        // then
        MemberReplica replica = repository.findByMemberId(MEMBER_ID).orElseThrow();
        assertThat(replica.getNickname()).isEqualTo(MemberReplica.WITHDRAWN_NICKNAME);
        assertThat(replica.getProfileImageUrl()).isNull();
        assertThat(replica.getMemberStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(replica.getSourceVersion()).isEqualTo(5L);
        assertThat(replica.getSyncedAt()).isEqualTo(SYNCED_AT);
    }

    @Test
    @DisplayName("닉네임과 프로필 이미지가 있는 회원의 탈퇴를 반영하면 표시 정보를 익명화하고 순서 값을 갱신한다")
    void upsertWithdrawn_existingReplica_anonymizes() {
        // given
        repository.upsertFromMemberUpdated(MEMBER_ID, "닉네임", "https://img/1.png", "ACTIVE", 1L, SYNCED_AT);

        // when
        repository.upsertWithdrawn(MEMBER_ID, 2L, LATER_SYNCED_AT);

        // then
        List<MemberReplica> replicas = repository.findAllByMemberIdIn(List.of(MEMBER_ID));
        assertThat(replicas).hasSize(1);
        MemberReplica replica = replicas.get(0);
        assertThat(replica.getNickname()).isEqualTo(MemberReplica.WITHDRAWN_NICKNAME);
        assertThat(replica.getProfileImageUrl()).isNull();
        assertThat(replica.getMemberStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(replica.getSourceVersion()).isEqualTo(2L);
        assertThat(replica.getSyncedAt()).isEqualTo(LATER_SYNCED_AT);
    }

    @Test
    @DisplayName("더 큰 순서 값의 정지 이벤트는 상태와 순서 값만 갱신하고 1을 반환한다")
    void updateStatusFromMemberSuspended_newerVersion_updatesStatus() {
        // given
        repository.upsertFromMemberUpdated(MEMBER_ID, "닉네임", null, "ACTIVE", 1L, SYNCED_AT);

        // when
        int updated = repository.updateStatusFromMemberSuspended(MEMBER_ID, "SUSPENDED", 2L, LATER_SYNCED_AT);

        // then
        assertThat(updated).isEqualTo(1);
        MemberReplica replica = repository.findByMemberId(MEMBER_ID).orElseThrow();
        assertThat(replica.getMemberStatus()).isEqualTo(MemberStatus.SUSPENDED);
        assertThat(replica.getSourceVersion()).isEqualTo(2L);
        assertThat(replica.getNickname()).isEqualTo("닉네임");
        assertThat(replica.getSyncedAt()).isEqualTo(LATER_SYNCED_AT);
    }

    @Test
    @DisplayName("저장된 순서 값과 같거나 작은 정지 이벤트는 0을 반환하고 상태를 바꾸지 않는다")
    void updateStatusFromMemberSuspended_sameOrOlderVersion_returnsZero() {
        // given
        repository.upsertFromMemberUpdated(MEMBER_ID, "닉네임", null, "ACTIVE", 3L, SYNCED_AT);

        // when
        int sameVersion = repository.updateStatusFromMemberSuspended(MEMBER_ID, "SUSPENDED", 3L, LATER_SYNCED_AT);
        int olderVersion = repository.updateStatusFromMemberSuspended(MEMBER_ID, "SUSPENDED", 2L, LATER_SYNCED_AT);

        // then
        assertThat(sameVersion).isZero();
        assertThat(olderVersion).isZero();
        assertThat(repository.findByMemberId(MEMBER_ID).orElseThrow().getMemberStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("탈퇴한 회원의 복제본에는 더 큰 순서 값의 정지 이벤트도 반영하지 않는다")
    void updateStatusFromMemberSuspended_withdrawnReplica_returnsZero() {
        // given
        repository.upsertWithdrawn(MEMBER_ID, 5L, SYNCED_AT);

        // when
        int updated = repository.updateStatusFromMemberSuspended(MEMBER_ID, "ACTIVE", 6L, LATER_SYNCED_AT);

        // then
        assertThat(updated).isZero();
        MemberReplica replica = repository.findByMemberId(MEMBER_ID).orElseThrow();
        assertThat(replica.getMemberStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(replica.getSourceVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("복제본이 없는 회원의 정지 이벤트는 0을 반환하고 행을 만들지 않는다")
    void updateStatusFromMemberSuspended_noReplica_returnsZero() {
        // when
        int updated = repository.updateStatusFromMemberSuspended(MEMBER_ID, "SUSPENDED", 2L, SYNCED_AT);

        // then
        assertThat(updated).isZero();
        assertThat(repository.findByMemberId(MEMBER_ID)).isEmpty();
    }
}
