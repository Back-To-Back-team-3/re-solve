package com.backtoback.problem.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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

import com.backtoback.problem.admin.store.AdminOutboxEventStore;
import com.backtoback.problem.admin.store.AdminOutboxEventStore.OutboxEventRow;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

@ExtendWith(MockitoExtension.class)
class AdminOutboxEventServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);

    @Mock
    private AdminOutboxEventStore store;
    @Mock
    private JdbcTemplate jdbc;
    @Spy
    private Clock clock = FIXED_CLOCK;
    @InjectMocks
    private AdminOutboxEventService service;

    @Test
    @DisplayName("FAILED 상태이고 7일 이내인 이벤트는 PENDING으로 전환하고 감사 기록을 남긴다")
    void reprocess_success() {
        var row = new OutboxEventRow(1L, "FAILED", NOW.minusDays(3));
        given(store.findById(1L)).willReturn(Optional.of(row));

        var id = service.reprocess(1L, 42L, "DLQ 복구", "127.0.0.1");

        assertThat(id).isEqualTo(1L);
        then(store).should().resetFailedToPending(eq(1L), any(LocalDateTime.class));
        then(jdbc)
            .should()
            .update(anyString(), eq(42L), eq("1"), eq("DLQ 복구"), eq("127.0.0.1"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("존재하지 않는 ID는 OUTBOX_EVENT_NOT_FOUND를 던진다")
    void reprocess_notFound_missingId() {
        given(store.findById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reprocess(999L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_FOUND)
            );
    }

    @Test
    @DisplayName("발생 7일을 초과한 이벤트는 OUTBOX_EVENT_NOT_FOUND를 던진다")
    void reprocess_notFound_expired() {
        var row = new OutboxEventRow(1L, "FAILED", NOW.minusDays(7).minusNanos(1000));
        given(store.findById(1L)).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.reprocess(1L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_FOUND)
            );
    }

    @Test
    @DisplayName("FAILED가 아닌 상태의 이벤트는 OUTBOX_EVENT_NOT_REPROCESSABLE을 던진다")
    void reprocess_notReprocessable_published() {
        var row = new OutboxEventRow(1L, "PUBLISHED", NOW.minusDays(1));
        given(store.findById(1L)).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.reprocess(1L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE)
            );
    }

    @Test
    @DisplayName("PENDING 상태의 이벤트도 OUTBOX_EVENT_NOT_REPROCESSABLE을 던진다")
    void reprocess_notReprocessable_pending() {
        var row = new OutboxEventRow(1L, "PENDING", NOW.minusDays(1));
        given(store.findById(1L)).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.reprocess(1L, 42L, "사유", "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE)
            );
    }
}
