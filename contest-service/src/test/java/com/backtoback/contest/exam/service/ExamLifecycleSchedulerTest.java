package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.backtoback.contest.exam.repository.ExamRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 회차별 대상 상한과 개별 시험의 실패 분리를 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamLifecycleSchedulerTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamLifecycleService lifecycle;

    /** 한 시험이 실패해도 같은 회차의 다른 시험을 처리한다. */
    @Test
    @DisplayName("대상 시험 한 건이 실패해도 다음 시험을 처리하고 조회는 100건으로 제한한다")
    void continuesAfterFailure() {
        // given
        given(exams.findLifecycleCandidates(any(), any())).willReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("failure")).when(lifecycle).transition(1L);
        var scheduler
            = new ExamLifecycleScheduler(
                exams,
                lifecycle,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry()
            );

        // when
        scheduler.run();

        // then
        verify(lifecycle).transition(1L);
        verify(lifecycle).transition(2L);
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(exams).findLifecycleCandidates(any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
    }
}
