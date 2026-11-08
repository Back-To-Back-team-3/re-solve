package com.backtoback.contest.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.admin.service.ExamRetryService;
import com.backtoback.contest.admin.service.ExamRetryStore;
import com.backtoback.contest.exam.service.ExamIntegrationTestSupport;
import com.backtoback.contest.operation.client.MemberOperationClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 원격 회원 작업 결과가 현재 가드에만 반영되는지 확인한다. */
class MemberGuardReconciliationIntegrationTest extends ExamIntegrationTestSupport {
    private static final long MEMBER_ID = 77771;
    @MockitoBean
    private MemberOperationClient memberOperations;
    @Autowired
    private MemberGuardReconciliation reconciliation;
    @Autowired
    private MemberGuardTransitions transitions;

    @BeforeEach
    void prepareGuard() {
        jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='MEMBER' AND resource_id=?", MEMBER_ID);
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO contest_resource_guards (resource_type,resource_id,operation_id,operation_type,"
                    + "expected_source_version,request_hash,state,prepared_from_state,operation_result,source_version,"
                    + "prepared_at,version,created_at,updated_at) "
                    + "VALUES ('MEMBER',?,'member-op','WITHDRAWAL',4,?,'PREPARED','OPEN','PENDING',4,?,0,?,?)",
                MEMBER_ID,
                "a".repeat(64),
                now,
                now,
                now
            );
    }

    @AfterEach
    void removeGuard() {
        jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='MEMBER' AND resource_id=?", MEMBER_ID);
    }

    @Test
    @DisplayName("원래 탈퇴 작업이 COMMITTED이면 회원 가드를 차단 상태로 종결한다")
    void committedBlocksGuard() {
        given(memberOperations.find(MEMBER_ID, "member-op"))
            .willReturn(new MemberOperationClient.Result("member-op", "COMMITTED", "5", "WITHDRAWN"));

        assertThat(reconciliation.reconcile(MEMBER_ID, "member-op", 4)).isTrue();
        assertThat(state()).isEqualTo("BLOCKED");
        assertThat(result()).isEqualTo("COMMITTED");
        assertThat(version()).isEqualTo(5);
    }

    @Test
    @DisplayName("원래 탈퇴 작업이 ABORTED이면 준비 전 가드 상태를 복원한다")
    void abortedRestoresGuard() {
        given(memberOperations.find(MEMBER_ID, "member-op"))
            .willReturn(new MemberOperationClient.Result("member-op", "ABORTED", "4", "ACTIVE"));

        assertThat(reconciliation.reconcile(MEMBER_ID, "member-op", 4)).isTrue();
        assertThat(state()).isEqualTo("OPEN");
        assertThat(result()).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("준비 전 차단 상태는 취소해도 차단으로 보존한다")
    void abortedRestoresBlockedState() {
        jdbc
            .update(
                "UPDATE contest_resource_guards SET prepared_from_state='BLOCKED' "
                    + "WHERE resource_type='MEMBER' AND resource_id=?",
                MEMBER_ID
            );
        given(memberOperations.find(MEMBER_ID, "member-op"))
            .willReturn(new MemberOperationClient.Result("member-op", "ABORTED", "4", "ACTIVE"));

        assertThat(reconciliation.reconcile(MEMBER_ID, "member-op", 4)).isTrue();
        assertThat(state()).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("완료 버전이 기대 버전보다 크지 않으면 준비 상태를 유지한다")
    void committedRequiresNewerVersion() {
        given(memberOperations.find(MEMBER_ID, "member-op"))
            .willReturn(new MemberOperationClient.Result("member-op", "COMMITTED", "4", "WITHDRAWN"));

        assertThat(reconciliation.reconcile(MEMBER_ID, "member-op", 4)).isFalse();
        assertThat(state()).isEqualTo("PREPARED");
    }

    @Test
    @DisplayName("탈퇴 이벤트만으로 준비 작업을 성공 처리하지 않고 취소 뒤에도 차단을 보존한다")
    void withdrawalEventDoesNotConfirmOperation() {
        transactions.executeWithoutResult(status -> transitions.observeWithdrawal(MEMBER_ID, 5));
        assertThat(state()).isEqualTo("PREPARED");
        assertThat(result()).isEqualTo("PENDING");
        given(memberOperations.find(MEMBER_ID, "member-op"))
            .willReturn(new MemberOperationClient.Result("member-op", "ABORTED", "4", "ACTIVE"));
        assertThat(reconciliation.reconcile(MEMBER_ID, "member-op", 4)).isTrue();
        assertThat(state()).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("PENDING과 404 및 통신 실패는 준비된 가드를 유지한다")
    void unknownMaintainsBlock() {
        given(memberOperations.find(MEMBER_ID, "member-op"))
            .willReturn(new MemberOperationClient.Result("member-op", "PENDING", "4", "ACTIVE"))
            .willThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND))
            .willThrow(new ResourceAccessException("timeout"));

        assertThat(reconciliation.reconcile(MEMBER_ID, "member-op", 4)).isFalse();
        assertThatThrownBy(() -> reconciliation.reconcile(MEMBER_ID, "member-op", 4))
            .isInstanceOf(HttpClientErrorException.class);
        assertThatThrownBy(() -> reconciliation.reconcile(MEMBER_ID, "member-op", 4))
            .isInstanceOf(ResourceAccessException.class);
        assertThat(state()).isEqualTo("PREPARED");
        assertThat(result()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("조회 중 다른 작업으로 바뀌면 이전 응답을 적용하지 않는다")
    void staleResponseCannotReplaceNewOperation() {
        given(memberOperations.find(MEMBER_ID, "member-op")).willAnswer(invocation -> {
            jdbc
                .update(
                    "UPDATE contest_resource_guards SET operation_id='new-op',expected_source_version=6 "
                        + "WHERE resource_type='MEMBER' AND resource_id=?",
                    MEMBER_ID
                );
            return new MemberOperationClient.Result("member-op", "COMMITTED", "5", "WITHDRAWN");
        });

        assertThat(reconciliation.reconcile(MEMBER_ID, "member-op", 4)).isFalse();
        assertThat(state()).isEqualTo("PREPARED");
        assertThat(result()).isEqualTo("PENDING");
    }

    /** 조회 실패 항목을 같은 회차에 반복하지 않고, 이후 유입한 작업보다 먼저 다시 조회한다. */
    @Test
    @DisplayName("신규 가드가 유입되어도 이전에 미확인한 가드를 다시 조회한다")
    void retriesUnknownGuardBeforeNewerCandidates() {
        // given
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).withNano(0).plusMinutes(3);
        var firstId
            = jdbc
                .queryForObject(
                    "SELECT id FROM contest_resource_guards WHERE resource_type='MEMBER' AND resource_id=?",
                    Long.class,
                    MEMBER_ID
                );
        given(memberOperations.find(MEMBER_ID, "member-op"))
            .willThrow(new ResourceAccessException("timeout"))
            .willReturn(new MemberOperationClient.Result("member-op", "ABORTED", "4", "ACTIVE"));
        long newerMember = MEMBER_ID + 1;
        given(memberOperations.find(newerMember, "newer-op"))
            .willReturn(new MemberOperationClient.Result("newer-op", "PENDING", "4", "ACTIVE"));

        // when
        runScheduler(now);
        var attemptedAt
            = jdbc
                .queryForObject(
                    "SELECT updated_at FROM contest_resource_guards WHERE id=?",
                    LocalDateTime.class,
                    firstId
                );
        assertThat(attemptedAt).isEqualTo(now);
        assertThat(state()).isEqualTo("PREPARED");
        try {
            jdbc
                .update(
                    "INSERT INTO contest_resource_guards (resource_type,resource_id,operation_id,operation_type,"
                        + "expected_source_version,request_hash,state,prepared_from_state,operation_result,"
                        + "source_version,prepared_at,version,created_at,updated_at) "
                        + "SELECT resource_type,?,'newer-op',operation_type,expected_source_version,request_hash,state,"
                        + "prepared_from_state,operation_result,source_version,?,0,?,? "
                        + "FROM contest_resource_guards WHERE id=?",
                    newerMember,
                    now.plusSeconds(1),
                    now.plusSeconds(1),
                    now.plusSeconds(1),
                    firstId
                );
            runScheduler(now.plusMinutes(5));

            // then
            var calls = inOrder(memberOperations);
            calls.verify(memberOperations, org.mockito.Mockito.times(2)).find(MEMBER_ID, "member-op");
            calls.verify(memberOperations).find(newerMember, "newer-op");
            assertThat(result()).isEqualTo("ABORTED");
            assertThat(state()).isEqualTo("OPEN");
        } finally {
            jdbc
                .update(
                    "DELETE FROM contest_resource_guards WHERE resource_type='MEMBER' AND resource_id=?",
                    newerMember
                );
        }
    }

    /** 응답과 확인 결과 저장이 실패해도 다음 회차는 이전 작업부터 다시 확인한다. */
    @Test
    @DisplayName("재처리 확인에 실패한 작업은 다음 대사 회차에서 다시 조회한다")
    void retriesUnconfirmedExamOperationInNextRun() {
        // given
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).withNano(0).plusMinutes(3);
        var store = mock(ExamRetryStore.class);
        var retry = mock(ExamRetryService.class);
        var operation
            = new ExamRetryStore.Operation(
                "retry-op",
                "EXAM_RETRY",
                "EXAM",
                Long.parseLong(examId),
                1L,
                "a".repeat(64),
                1,
                null,
                "PENDING",
                7L,
                "재처리",
                null,
                now.minusMinutes(2)
            );
        jdbc
            .update(
                "INSERT INTO contest_admin_operations (operation_id,operation_type,context,context_id,receipt_id,"
                    + "request_hash,expected_judge_attempt,state,actor_member_id,reason,created_at,updated_at) "
                    + "VALUES ('retry-op','EXAM_RETRY','EXAM',?,1,?,1,'PENDING',7,'재처리',?,?)",
                examId,
                operation.hash(),
                operation.updatedAt(),
                operation.updatedAt()
            );
        given(store.require("retry-op")).willReturn(operation);
        given(retry.reconcile(operation)).willThrow(new IllegalStateException("대사 결과 저장 실패"));

        // when
        runScheduler(now, store, retry);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT updated_at FROM contest_admin_operations WHERE operation_id='retry-op'",
                    LocalDateTime.class
                )
        ).isEqualTo(now);
        runScheduler(now.plusMinutes(5), store, retry);

        // then
        org.mockito.Mockito.verify(retry, org.mockito.Mockito.times(2)).reconcile(operation);
    }

    private void runScheduler(LocalDateTime now) {
        runScheduler(now, mock(ExamRetryStore.class), mock(ExamRetryService.class));
    }

    private void runScheduler(LocalDateTime now, ExamRetryStore store, ExamRetryService retry) {
        var locks = mock(ExtensibleLockProvider.class);
        given(locks.lock(org.mockito.ArgumentMatchers.any())).willReturn(Optional.of(mock(SimpleLock.class)));
        var scheduler
            = new ContestOperationReconciliationScheduler(
                jdbc,
                store,
                retry,
                reconciliation,
                mock(ResourceGuardReconciliation.class),
                Clock.fixed(now.atZone(ClockConfiguration.SERVICE_ZONE).toInstant(), ClockConfiguration.SERVICE_ZONE),
                new SimpleMeterRegistry(),
                locks
            );
        try {
            scheduler.run();
        } finally {
            scheduler.stopRenewals();
        }
    }

    private String state() {
        return jdbc
            .queryForObject(
                "SELECT state FROM contest_resource_guards " + "WHERE resource_type='MEMBER' AND resource_id=?",
                String.class,
                MEMBER_ID
            );
    }

    private String result() {
        return jdbc
            .queryForObject(
                "SELECT operation_result FROM contest_resource_guards "
                    + "WHERE resource_type='MEMBER' AND resource_id=?",
                String.class,
                MEMBER_ID
            );
    }

    private long version() {
        return jdbc
            .queryForObject(
                "SELECT source_version FROM contest_resource_guards "
                    + "WHERE resource_type='MEMBER' AND resource_id=?",
                Long.class,
                MEMBER_ID
            );
    }
}
