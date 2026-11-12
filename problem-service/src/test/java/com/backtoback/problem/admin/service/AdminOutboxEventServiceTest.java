package com.backtoback.problem.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.problem.admin.store.AdminOutboxEventStore;
import com.backtoback.problem.admin.store.AdminOutboxEventStore.OutboxEventRow;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

@ExtendWith(MockitoExtension.class)
class AdminOutboxEventServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW
        = LocalDateTime.ofInstant(FIXED_CLOCK.instant(), ClockConfiguration.SERVICE_ZONE);

    @Mock
    private AdminOutboxEventStore store;
    @Mock
    private JdbcTemplate jdbc;
    @Spy
    private Clock clock = FIXED_CLOCK;
    @InjectMocks
    private AdminOutboxEventService service;

    /** 명세의 성공·거절 조건과 감사 저장 여부를 확인한다. */
    @Test
    @DisplayName("FAILED 상태이고 7일 이내인 이벤트는 PENDING으로 전환하고 감사 기록을 남긴다")
    void reprocess_success() {
        var row = new OutboxEventRow(1L, "FAILED", 6, NOW.minusDays(3), null);
        given(store.lockById(1L)).willReturn(Optional.of(row));
        given(store.resetFailedToPending(1L, NOW, NOW.minusDays(7))).willReturn(true);

        var id = service.reprocess(1L, 42L, "DLQ 복구", "127.0.0.1");

        assertThat(id).isEqualTo(1L);
        then(store).should().resetFailedToPending(1L, NOW, NOW.minusDays(7));
        then(jdbc)
            .should()
            .update(
                anyString(),
                eq(42L),
                eq("1"),
                eq("FAILED"),
                eq(6),
                org.mockito.ArgumentMatchers.isNull(),
                eq("DLQ 복구"),
                eq("127.0.0.1"),
                eq(NOW)
            );
    }

    /** 명세의 성공·거절 조건과 감사 저장 여부를 확인한다. */
    @Test
    @DisplayName("존재하지 않는 ID는 OUTBOX_EVENT_NOT_FOUND를 던진다")
    void reprocess_notFound_missingId() {
        given(store.lockById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reprocess(999L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_FOUND)
            );
    }

    /** 명세의 성공·거절 조건과 감사 저장 여부를 확인한다. */
    @Test
    @DisplayName("발생 7일을 초과한 이벤트는 OUTBOX_EVENT_NOT_FOUND를 던진다")
    void reprocess_notFound_expired() {
        var row = new OutboxEventRow(1L, "FAILED", 6, NOW.minusDays(7).minusNanos(1000), null);
        given(store.lockById(1L)).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.reprocess(1L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_FOUND)
            );
    }

    /** 명세의 성공·거절 조건과 감사 저장 여부를 확인한다. */
    @Test
    @DisplayName("FAILED가 아닌 상태의 이벤트는 OUTBOX_EVENT_NOT_REPROCESSABLE을 던진다")
    void reprocess_notReprocessable_published() {
        var row = new OutboxEventRow(1L, "PUBLISHED", 6, NOW.minusDays(1), null);
        given(store.lockById(1L)).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.reprocess(1L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE)
            );
    }

    /** 명세의 성공·거절 조건과 감사 저장 여부를 확인한다. */
    @Test
    @DisplayName("PENDING 상태의 이벤트도 OUTBOX_EVENT_NOT_REPROCESSABLE을 던진다")
    void reprocess_notReprocessable_pending() {
        var row = new OutboxEventRow(1L, "PENDING", 6, NOW.minusDays(1), null);
        given(store.lockById(1L)).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.reprocess(1L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE)
            );
    }

    /** 조건부 전환이 실패하면 감사와 성공 응답을 남기지 않는다. */
    @Test
    @DisplayName("잠근 행의 조건부 갱신 결과가 0건이면 409로 거절하고 감사하지 않는다")
    void rejectsUnchangedRow() {
        given(store.lockById(1L)).willReturn(Optional.of(new OutboxEventRow(1L, "FAILED", 6, NOW.minusDays(1), null)));
        assertThatThrownBy(() -> service.reprocess(1L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE)
            );
        verifyNoInteractions(jdbc);
    }

    /** 발생 7일 경계는 포함하여 재처리한다. */
    @Test
    @DisplayName("발생 시각이 정확히 7일 전이면 재처리를 허용한다")
    void acceptsExactBoundary() {
        given(store.lockById(1L)).willReturn(Optional.of(new OutboxEventRow(1L, "FAILED", 6, NOW.minusDays(7), null)));
        given(store.resetFailedToPending(1L, NOW, NOW.minusDays(7))).willReturn(true);
        assertThat(service.reprocess(1L, 42L, "사유", "127.0.0.1")).isEqualTo(1L);
    }

    /** 잠금 대기로 유효 기간이 지난 이벤트를 이전 시각으로 허용하지 않는다. */
    @Test
    @DisplayName("잠금 획득 후 시각으로 유효 기간을 다시 확인한다")
    void checksTimeAfterLock() {
        given(store.lockById(1L)).willAnswer(invocation -> {
            given(clock.instant()).willReturn(FIXED_CLOCK.instant().plusSeconds(1));
            return Optional.of(new OutboxEventRow(1L, "FAILED", 6, NOW.minusDays(7), null));
        });
        assertThatThrownBy(() -> service.reprocess(1L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_FOUND)
            );
        verifyNoInteractions(jdbc);
    }
}
