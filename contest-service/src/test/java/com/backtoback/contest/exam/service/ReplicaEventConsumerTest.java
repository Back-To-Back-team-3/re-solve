package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.contest.exam.client.MemberClient;
import com.backtoback.contest.exam.client.MemberStatusResponse;

import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;

/**
 * 회원·스터디 이벤트의 복제본 저장과 소비 기록 및 ACK 경계를 검증한다.
 */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ReplicaEventConsumerTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private EventConsumer consumer;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MemberReplicaService memberReplicaService;
    @MockitoBean
    private MemberClient members;

    /** 현재 상태 응답은 표시 정보가 없어도 확인하되 복제본을 생성하지 않는다. */
    @Test
    @DisplayName("복제본 필드 없는 활성 회원 조회는 상태를 반환하고 복제본을 만들지 않는다")
    void statusWithoutSnapshotDoesNotCreateReplica() {
        // given
        jdbc.update("DELETE FROM member_replicas WHERE member_id=420");
        given(members.fetch(420L)).willReturn(new MemberStatusResponse("420", "ACTIVE", "USER", null, null, null));

        // when
        var status = memberReplicaService.refresh(420L);

        // then
        assertThat(status).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_replicas WHERE member_id=420", Integer.class))
            .isZero();
    }

    /** 정지 상태는 이전 활성 복제본보다 우선해 신규 요청을 차단할 수 있다. */
    @Test
    @DisplayName("현재 조회가 정지 상태이면 활성 복제본을 갱신하지 않고 정지를 반환한다")
    void statusWithoutSnapshotDoesNotOverrideReplica() {
        // given
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberUpdated",
                    "421",
                    "\"memberId\":\"421\",\"sourceVersion\":3,\"status\":\"ACTIVE\",\"nickname\":\"기존\""
                ),
                mock(Runnable.class)
            );
        given(members.fetch(421L)).willReturn(new MemberStatusResponse("421", "SUSPENDED", "USER", null, null, null));

        // when & then
        assertThat(memberReplicaService.refresh(421L)).isEqualTo("SUSPENDED");
        var row = jdbc.queryForMap("SELECT member_status,source_version FROM member_replicas WHERE member_id=421");
        assertThat(row.get("member_status")).isEqualTo("ACTIVE");
        assertThat(((Number)row.get("source_version")).longValue()).isEqualTo(3);
    }

    /** 버전이 있는 오래된 정지 응답은 더 최신 활성 복제본을 덮지 않는다. */
    @Test
    @DisplayName("오래된 정지 스냅샷보다 최신 활성 복제본의 상태를 사용한다")
    void olderSuspensionSnapshotDoesNotReplaceActiveReplica() {
        // given
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberUpdated",
                    "423",
                    "\"memberId\":\"423\",\"sourceVersion\":3,\"status\":\"ACTIVE\",\"nickname\":\"최신\""
                ),
                mock(Runnable.class)
            );
        given(members.fetch(423L)).willReturn(new MemberStatusResponse("423", "SUSPENDED", "USER", 2L, "이전", null));

        // when & then
        assertThat(memberReplicaService.refresh(423L)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT source_version FROM member_replicas WHERE member_id=423", Long.class))
            .isEqualTo(3L);
    }

    /** 표시 정보가 아직 없으면 동일 이벤트를 최초 회원 정보 반영 뒤 다시 처리할 수 있도록 롤백한다. */
    @Test
    @DisplayName("정지 이벤트는 복제본이 생긴 뒤 재수신하면 상태를 반영하고 ACK한다")
    void retriesSuspensionAfterReplicaCreation() {
        // given
        var listener = new ReplicaEventListener(consumer);
        var ack = mock(Acknowledgement.class);
        var eventId = UUID.randomUUID().toString();
        var message = suspensionEvent(eventId, "422", 2, true);

        // when & then
        assertThatThrownBy(() -> listener.receive(message, ack))
            .isInstanceOf(InvalidDataAccessApiUsageException.class)
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessage("Member replica is required for suspension event");
        assertThat(processed(eventId)).isZero();
        verify(ack, never()).acknowledge();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_replicas WHERE member_id=422", Integer.class))
            .isZero();

        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberUpdated",
                    "422",
                    "\"memberId\":\"422\",\"sourceVersion\":1,\"status\":\"ACTIVE\",\"nickname\":\"이름\""
                ),
                mock(Runnable.class)
            );
        listener.receive(message, ack);
        assertThat(jdbc.queryForObject("SELECT member_status FROM member_replicas WHERE member_id=422", String.class))
            .isEqualTo("SUSPENDED");
        assertThat(processed(eventId)).isEqualTo(1);
        verify(ack).acknowledge();
        verifyNoInteractions(members);
    }

    @Test
    @DisplayName("조회 중 더 최신 이벤트가 저장돼 있으면 저장된 회원 상태를 반환한다")
    void refreshReturnsNewerStoredStatus() {
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberUpdated",
                    "408",
                    "\"memberId\":\"408\",\"sourceVersion\":3,\"status\":\"SUSPENDED\"," + "\"nickname\":\"최신\""
                ),
                mock(Runnable.class)
            );
        given(members.fetch(408L)).willReturn(new MemberStatusResponse("408", "ACTIVE", "USER", 2L, "이전", null));
        assertThat(memberReplicaService.refresh(408L)).isEqualTo("SUSPENDED");
    }

    /** 정지 이벤트에 표시 정보가 없어도 기존 닉네임과 이미지를 보존한다. */
    @Test
    @DisplayName("정지 이벤트는 회원 조회 없이 기존 표시 정보를 보존하고 정지 상태를 저장한다")
    void suspensionKeepsDisplayInformation() {
        // given
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberUpdated",
                    "401",
                    "\"memberId\":\"401\",\"sourceVersion\":10,\"status\":\"ACTIVE\","
                        + "\"nickname\":\"기존\",\"profileImageUrl\":\"image\""
                ),
                mock(Runnable.class)
            );
        var listener = new ReplicaEventListener(consumer);
        var ack = mock(Acknowledgement.class);
        var eventId = UUID.randomUUID().toString();

        // when
        listener.receive(suspensionEvent(eventId, "401", 11, true), ack);

        // then
        var row
            = jdbc
                .queryForMap(
                    "SELECT nickname, profile_image_url, member_status, source_version "
                        + "FROM member_replicas WHERE member_id = 401"
                );
        assertThat(row.get("nickname")).isEqualTo("기존");
        assertThat(row.get("profile_image_url")).isEqualTo("image");
        assertThat(row.get("member_status")).isEqualTo("SUSPENDED");
        assertThat(((Number)row.get("source_version")).longValue()).isEqualTo(11L);
        assertThat(processed(eventId)).isEqualTo(1);
        verify(ack).acknowledge();
        verifyNoInteractions(members);
    }

    /** 정지 해제의 상태와 소비 기록을 한 번만 반영하고 재전달에도 ACK한다. */
    @Test
    @DisplayName("정지 해제 이벤트가 활성 상태로 갱신되고 중복 수신은 회원을 다시 조회하지 않는다")
    void resumeAndDuplicate() {
        // given
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberUpdated",
                    "410",
                    "\"memberId\":\"410\",\"sourceVersion\":1,\"status\":\"SUSPENDED\"," + "\"nickname\":\"이름\""
                ),
                mock(Runnable.class)
            );
        var listener = new ReplicaEventListener(consumer);
        var ack = mock(Acknowledgement.class);
        var eventId = UUID.randomUUID().toString();
        var message = suspensionEvent(eventId, "410", 2, false);
        // when
        listener.receive(message, ack);
        listener.receive(message, ack);

        // then
        assertThat(jdbc.queryForObject("SELECT member_status FROM member_replicas WHERE member_id=410", String.class))
            .isEqualTo("ACTIVE");
        assertThat(processed(eventId)).isEqualTo(1);
        verifyNoInteractions(members);
        verify(ack, times(2)).acknowledge();
    }

    /** 과거 또는 같은 버전의 해제가 저장된 정지 상태를 바꾸지 않도록 한다. */
    @Test
    @DisplayName("과거 버전이나 같은 버전의 정지·해제는 저장된 상태를 유지하고 ACK한다")
    void staleOrEqualSuspensionKeepsStoredStatus() {
        // given
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberUpdated",
                    "402",
                    "\"memberId\":\"402\",\"sourceVersion\":3,\"status\":\"SUSPENDED\",\"nickname\":\"이름\""
                ),
                mock(Runnable.class)
            );
        var listener = new ReplicaEventListener(consumer);
        var ack = mock(Acknowledgement.class);
        var eventId = UUID.randomUUID().toString();
        var equalId = UUID.randomUUID().toString();

        // when
        listener.receive(suspensionEvent(eventId, "402", 2, false), ack);
        listener.receive(suspensionEvent(equalId, "402", 3, false), ack);

        // then
        assertThat(jdbc.queryForObject("SELECT member_status FROM member_replicas WHERE member_id=402", String.class))
            .isEqualTo("SUSPENDED");
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(processed(equalId)).isEqualTo(1);
        verify(ack, times(2)).acknowledge();
    }

    /** 탈퇴가 저장된 회원은 정지 해제 이벤트의 순번이 더 높아도 복원하지 않는다. */
    @Test
    @DisplayName("탈퇴한 회원의 복제본은 이후 정지 해제 이벤트로 복원되지 않는다")
    void resumeDoesNotRestoreWithdrawnMember() {
        // given
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberWithdrawn",
                    "409",
                    "\"memberId\":\"409\",\"sourceVersion\":2"
                ),
                mock(Runnable.class)
            );
        var listener = new ReplicaEventListener(consumer);
        var ack = mock(Acknowledgement.class);
        var eventId = UUID.randomUUID().toString();
        var message = suspensionEvent(eventId, "409", 3, false);

        // when & then
        listener.receive(message, ack);
        var row
            = jdbc
                .queryForMap("SELECT nickname, member_status, source_version FROM member_replicas WHERE member_id=409");
        assertThat(row.get("nickname")).isEqualTo("탈퇴한 사용자");
        assertThat(row.get("member_status")).isEqualTo("WITHDRAWN");
        assertThat(((Number)row.get("source_version")).longValue()).isEqualTo(2L);
        assertThat(processed(eventId)).isEqualTo(1);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("업무 저장 오류는 소비 기록을 롤백하고 ACK하지 않는다")
    void databaseFailureRollsBack() {
        var ack = mock(Runnable.class);
        var eventId = UUID.randomUUID().toString();
        var message
            = event(
                eventId,
                "MemberUpdated",
                "406",
                "\"memberId\":\"406\"," + "\"sourceVersion\":1,\"status\":\"ACTIVE\",\"nickname\":\"" + "x".repeat(51)
                    + "\""
            );
        assertThatThrownBy(() -> consumer.consume(message, ack)).isInstanceOf(RuntimeException.class);
        assertThat(processed(eventId)).isZero();
        verify(ack, never()).run();
    }

    @Test
    @DisplayName("탈퇴가 최초 도착해도 익명화하고 늦은 회원 변경을 무시한다")
    void withdrawalAnonymizesBeforeOlderUpdate() {
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberWithdrawn",
                    "407",
                    "\"memberId\":\"407\",\"sourceVersion\":4"
                ),
                mock(Runnable.class)
            );
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "MemberUpdated",
                    "407",
                    "\"memberId\":\"407\",\"sourceVersion\":3,\"status\":\"ACTIVE\","
                        + "\"nickname\":\"이전\",\"profileImageUrl\":\"old\""
                ),
                mock(Runnable.class)
            );
        var row
            = jdbc
                .queryForMap(
                    "SELECT nickname, profile_image_url, member_status FROM member_replicas " + "WHERE member_id = 407"
                );
        assertThat(row.get("nickname")).isEqualTo("탈퇴한 사용자");
        assertThat(row.get("profile_image_url")).isNull();
        assertThat(row.get("member_status")).isEqualTo("WITHDRAWN");
    }

    @Test
    @DisplayName("같은 가입의 탈퇴가 먼저 와도 늦은 가입과 중복 수신은 복원하지 않는다")
    void leftAndDuplicateRemainInactive() {
        var ack = mock(Runnable.class);
        var leftId = UUID.randomUUID().toString();
        var left
            = event(
                leftId,
                "StudyMemberLeft",
                "403",
                "\"studyId\":\"404\"," + "\"memberId\":\"405\",\"membershipId\":\"403\""
            );
        consumer.consume(left, ack);
        consumer.consume(left, ack);
        consumer
            .consume(
                event(
                    UUID.randomUUID().toString(),
                    "StudyMemberJoined",
                    "403",
                    "\"studyId\":\"404\"," + "\"memberId\":\"405\",\"membershipId\":\"403\""
                ),
                ack
            );
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT is_active FROM study_membership_replicas " + "WHERE study_id=404 AND member_id=405",
                    Boolean.class
                )
        ).isFalse();
        assertThat(processed(leftId)).isEqualTo(1);
    }

    private int processed(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    /** 회원 서비스가 발행하는 표시 정보 없는 정지·해제 이벤트를 구성한다 (이벤트 계약서 4.1 M2). */
    private String suspensionEvent(String eventId, String memberId, long sourceVersion, boolean suspended) {
        var payload = """
            "memberId":"%s","sourceVersion":%d,"suspended":%b,"changedAt":"2026-11-03T09:00:00.000000+09:00"
            """.formatted(memberId, sourceVersion, suspended).strip();
        return event(eventId, "MemberSuspended", memberId, payload);
    }

    private String event(String eventId, String type, String aggregateId, String payload) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type
            + "\",\"schemaVersion\":\"v1\",\"occurredAt\":\"2026-11-03T09:00:00.000000+09:00\"," + "\"aggregateId\":\""
            + aggregateId + "\",\"correlationId\":\"test\"," + payload + "}";
    }
}
