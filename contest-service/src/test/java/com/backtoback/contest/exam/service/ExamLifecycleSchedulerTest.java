package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.ExamRepository.LifecycleCandidate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 회차별 대상 상한과 개별 시험의 실패 분리를 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamLifecycleSchedulerTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamLifecycleService lifecycle;
    @Mock
    private ExtensibleLockProvider locks;
    @Mock
    private SimpleLock lock;

    /** 한 시험이 실패해도 같은 회차의 다른 시험을 처리한다. */
    @Test
    @DisplayName("대상 시험 한 건이 실패해도 다음 시험을 처리하고 조회는 100건으로 제한한다")
    void continuesAfterFailure() {
        // given
        var due = LocalDateTime.parse("2026-01-01T00:00:00");
        var candidates = List.of(candidate(1, due), candidate(2, due));
        given(locks.lock(any())).willReturn(Optional.of(lock));
        given(exams.findLifecycleCandidates(any(), isNull(), eq(0L), any())).willReturn(candidates);
        doThrow(new IllegalStateException("failure")).when(lifecycle).transition(1L);
        var scheduler
            = new ExamLifecycleScheduler(exams, lifecycle, Clock.systemUTC(), new SimpleMeterRegistry(), locks);

        // when
        try {
            scheduler.run();
        } finally {
            scheduler.stopRenewals();
        }

        // then
        verify(lifecycle).transition(1L);
        verify(lifecycle).transition(2L);
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(exams).findLifecycleCandidates(any(), isNull(), eq(0L), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("앞 100건이 실패해도 뒤 시험을 처리하고 끝에서 실패 후보를 다시 조회한다")
    void resumesAfterFailedPageAndWraps() {
        var due = LocalDateTime.parse("2026-01-01T00:00:00");
        var firstPage = LongStream.rangeClosed(1, 100).mapToObj(id -> candidate(id, due)).toList();
        var nextPage = List.of(candidate(101, due));
        given(locks.lock(any())).willReturn(Optional.of(lock));
        willReturn(firstPage, List.of(firstPage.getFirst()))
            .given(exams)
            .findLifecycleCandidates(any(), isNull(), eq(0L), any());
        willReturn(nextPage).given(exams).findLifecycleCandidates(any(), eq(due), eq(100L), any());
        willReturn(List.of()).given(exams).findLifecycleCandidates(any(), eq(due), eq(101L), any());
        doAnswer(invocation -> {
            if (invocation.<Long>getArgument(0) <= 100) {
                throw new IllegalStateException("failure");
            }
            return null;
        }).when(lifecycle).transition(anyLong());
        var scheduler
            = new ExamLifecycleScheduler(exams, lifecycle, Clock.systemUTC(), new SimpleMeterRegistry(), locks);
        try {
            scheduler.run();
            scheduler.run();
            scheduler.run();
        } finally {
            scheduler.stopRenewals();
        }

        var order = inOrder(exams, lifecycle);
        order.verify(exams).findLifecycleCandidates(any(), isNull(), eq(0L), any());
        order.verify(exams).findLifecycleCandidates(any(), eq(due), eq(100L), any());
        order.verify(lifecycle).transition(101L);
        order.verify(exams).findLifecycleCandidates(any(), eq(due), eq(101L), any());
        order.verify(exams).findLifecycleCandidates(any(), isNull(), eq(0L), any());
        order.verify(lifecycle).transition(1L);
    }

    @Test
    @DisplayName("다른 인스턴스가 잠금을 보유하면 후보를 조회하지 않는다")
    void skipsWhenLockIsHeld() {
        given(locks.lock(any())).willReturn(Optional.empty());
        var scheduler
            = new ExamLifecycleScheduler(exams, lifecycle, Clock.systemUTC(), new SimpleMeterRegistry(), locks);
        try {
            scheduler.run();
        } finally {
            scheduler.stopRenewals();
        }
        verifyNoInteractions(exams, lifecycle);
    }

    private LifecycleCandidate candidate(long id, LocalDateTime due) {
        var candidate = mock(LifecycleCandidate.class);
        given(candidate.getId()).willReturn(id);
        given(candidate.getDueAt()).willReturn(due);
        return candidate;
    }
}
