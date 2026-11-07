package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.contest.global.error.ContestException;

/** 시험 결과 버전으로 전달된 재연결 위치를 확인한다. */
class ExamEventStreamServiceTest {
    @Test
    @DisplayName("두 구독의 조회가 지연돼도 다른 구독의 다음 조회를 막지 않는다")
    void slowSubscribersDoNotBlockAnotherRead() throws Exception {
        var snapshots = mock(ExamEventSnapshotService.class);
        var slowReads = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var freshRead = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>();
        when(snapshots.read(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("7")))
            .thenAnswer(invocation -> {
                String exam = invocation.getArgument(0);
                int count
                    = calls
                        .computeIfAbsent(exam, key -> new java.util.concurrent.atomic.AtomicInteger())
                        .incrementAndGet();
                if (count > 1 && !"57".equals(exam)) {
                    slowReads.countDown();
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Read was not released");
                    }
                }
                if (count > 1 && "57".equals(exam)) {
                    freshRead.countDown();
                }
                return new ExamEventSnapshotService.Snapshot(exam, "CLOSED", count, OffsetDateTime.now(), count, count);
            });
        var stream = new ExamEventStreamService(snapshots);
        try {
            stream.subscribe("55", "7", null);
            stream.subscribe("56", "7", null);
            assertThat(slowReads.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            stream.subscribe("57", "7", null);
            assertThat(freshRead.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            stream.close();
        }
    }

    private final ExamEventSnapshotService.Snapshot snapshot
        = new ExamEventSnapshotService.Snapshot("55", "CLOSED", 3, OffsetDateTime.now(), 1, 2);

    /** 같은 버전도 상태·결과 중 어느 이벤트까지 받았는지 모르므로 현재 상태를 다시 보낸다. */
    @Test
    @DisplayName("같은 시험의 이전 또는 현재 버전은 재연결할 수 있다")
    void acceptsCurrentAndEarlierVersion() {
        assertThatCode(() -> ExamEventStreamService.validateCursor(null, snapshot)).doesNotThrowAnyException();
        assertThatCode(() -> ExamEventStreamService.validateCursor("EXAM:55:0", snapshot)).doesNotThrowAnyException();
        assertThatCode(() -> ExamEventStreamService.validateCursor("EXAM:55:3", snapshot)).doesNotThrowAnyException();
    }

    /** 다른 시험, 잘못된 형식과 미래 버전은 거절한다. */
    @Test
    @DisplayName("다른 시험과 미래 또는 잘못된 결과 버전은 거절한다")
    void rejectsInvalidCursor() {
        for (String cursor : new String[] {
            "EXAM:56:3",
            "EXAM:55:4",
            "CONTEST:55:3",
            "EXAM:55:-1",
            "EXAM:55:x"
        }) {
            assertThatThrownBy(() -> ExamEventStreamService.validateCursor(cursor, snapshot))
                .isInstanceOf(ContestException.class);
        }
    }

    /** 잘못된 커서의 판별보다 참가 권한 확인이 먼저다. */
    @Test
    @DisplayName("재연결 커서를 검사하기 전에 본인 참가 권한을 확인한다")
    void checksAuthorizationFirst() {
        var snapshots = mock(ExamEventSnapshotService.class);
        var failure = mock(ContestException.class);
        when(snapshots.read("55", "7")).thenThrow(failure);
        var stream = new ExamEventStreamService(snapshots);
        try {
            assertThatThrownBy(() -> stream.subscribe("55", "7", "EXAM:56:0")).isSameAs(failure);
        } finally {
            stream.close();
        }
    }
}
