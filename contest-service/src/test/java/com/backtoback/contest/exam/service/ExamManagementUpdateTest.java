package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.exam.client.ExamDependencyClient;
import com.backtoback.contest.exam.client.ExamDependencyClient.ProblemSummaries;
import com.backtoback.contest.exam.client.ExamDependencyClient.ProblemSummary;
import com.backtoback.contest.exam.client.ExamDependencyClient.StudyMember;
import com.backtoback.contest.exam.client.ExamDependencyClient.StudyMembers;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.dto.ExamCreateRequest.Problem;
import com.backtoback.contest.exam.dto.ExamUpdateRequest;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.LockedGuard;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 시험 수정 시 버전, 시작 시각, 참가자와 Outbox 갱신을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamManagementUpdateTest {
    @Mock
    private ExamDependencyClient dependencies;
    @Mock
    private MemberReplicaService members;
    @Mock
    private ContestResourceGuardRepository guards;
    @Mock
    private ProblemSnapshotService snapshots;
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamProblemRepository problems;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private OutboxEventWriter outbox;
    @Mock
    private TransactionTemplate transactions;
    private ExamManagementService service;
    private Exam exam;

    @BeforeEach
    void setUp() {
        service
            = new ExamManagementService(
                dependencies,
                members,
                guards,
                snapshots,
                exams,
                problems,
                jdbc,
                outbox,
                transactions,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
            );
        exam
            = Exam
                .create(
                    12L,
                    7L,
                    "기존",
                    ExamMode.FIXED,
                    LocalDateTime.parse("2026-01-01T10:00:00"),
                    LocalDateTime.parse("2026-01-01T11:00:00"),
                    null,
                    1,
                    new BigDecimal("100.00")
                );
        ReflectionTestUtils.setField(exam, "id", 55L);
        lenient().when(exams.findById(55L)).thenReturn(Optional.of(exam));
        lenient().when(exams.findForUpdate(55L)).thenReturn(Optional.of(exam));
        lenient().when(members.refresh(7)).thenReturn("ACTIVE");
        lenient()
            .when(dependencies.study(12))
            .thenReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "8", "MANAGER"))));
        lenient()
            .when(dependencies.problems(List.of(1L)))
            .thenReturn(
                new ProblemSummaries(
                    List.of(new ProblemSummary("1", true, "GENERAL", "PUBLIC", 3L, "10", 2, "문제", List.of("java")))
                )
            );
        lenient()
            .when(guards.lockAll(any()))
            .thenReturn(
                List
                    .of(
                        new LockedGuard(new Resource("PROBLEM", 1), "OPEN"),
                        new LockedGuard(new Resource("STUDY", 12), "OPEN")
                    )
            );
        lenient()
            .when(jdbc.queryForMap(anyString(), any(Object.class)))
            .thenReturn(
                Map
                    .of(
                        "state",
                        "OPEN",
                        "source_version",
                        3L,
                        "source_revision_id",
                        10L,
                        "source_scope",
                        "GENERAL",
                        "source_status",
                        "PUBLIC"
                    )
            );
        lenient().when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    /** 현재 탈퇴한 회원은 설정 변경을 시작하지 않는다. */
    @Test
    @DisplayName("현재 회원이 탈퇴 상태이면 시험 설정 변경을 거절한다")
    void rejectsWithdrawnManager() {
        // given
        given(members.refresh(7)).willReturn("WITHDRAWN");

        // when & then
        assertThatThrownBy(() -> service.update("55", "7", request("0")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("버전이 달라지면 참가자와 문제를 변경하지 않는다")
    void rejectsVersionConflict() {
        ReflectionTestUtils.setField(exam, "version", 2L);
        assertThatThrownBy(() -> service.update("55", "7", request("0")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_REVISION_CONFLICT)
            );
        verifyNoInteractions(problems, outbox);
    }

    @Test
    @DisplayName("기존 시작 시각에 도달하면 스케줄러 상태가 지연돼도 수정을 거절한다")
    void rejectsWhenOldStartReached() {
        ReflectionTestUtils.setField(exam, "startsAt", LocalDateTime.parse("2026-01-01T09:00:00"));
        assertThatThrownBy(() -> service.update("55", "7", request("0")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_MODIFICATION_CLOSED)
            );
        verifyNoInteractions(problems, outbox);
    }

    @Test
    @DisplayName("활성 참가자 마감과 문제를 교체하고 전체 스냅샷을 Outbox에 저장한다")
    void updatesParticipantsAndWritesSnapshot() {
        given(jdbc.queryForList(anyString(), eq(Long.class), eq(55L))).willReturn(List.of(7L, 9L));
        var response = service.update("55", "7", request("0"));
        assertThat(response.examRevision()).isEqualTo("2");
        verify(problems).deleteForExam(55L);
        verify(problems).saveAll(any());
        verify(jdbc).update(anyString(), any(), any(), eq(55L));
        var payload = ArgumentCaptor.forClass(ExamManagementService.UpdatedPayload.class);
        verify(outbox).append(eq("EXAM"), eq("ExamUpdated"), eq("v1"), eq("55"), anyString(), payload.capture());
        assertThat(payload.getValue().targetMemberIds()).containsExactly("7", "9");
    }

    private ExamUpdateRequest request(String version) {
        return new ExamUpdateRequest(
            version,
            "변경",
            OffsetDateTime.parse("2026-01-01T02:00:00Z"),
            OffsetDateTime.parse("2026-01-01T03:00:00Z"),
            List.of(new Problem("1", "100.00", 1))
        );
    }
}
