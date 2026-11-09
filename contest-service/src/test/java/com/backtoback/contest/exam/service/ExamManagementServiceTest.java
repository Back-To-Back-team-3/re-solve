package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import com.backtoback.contest.exam.dto.ExamCreateRequest;
import com.backtoback.contest.exam.dto.ExamCreateRequest.Problem;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.LockedGuard;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 시험 생성 요청의 입력과 스터디 권한을 저장 전에 검증한다. */
@ExtendWith(MockitoExtension.class)
class ExamManagementServiceTest {
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
    }

    @Test
    @DisplayName("중복 문제는 외부 조회 전에 잘못된 요청으로 거절한다")
    void rejectsDuplicateProblem() {
        var request = request(List.of(new Problem("1", "50.00", 1), new Problem("1", "50.00", 2)));
        assertThatThrownBy(() -> service.create("12", "7", request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        verifyNoInteractions(dependencies, members, transactions);
    }

    @Test
    @DisplayName("0점과 배점 합계 초과, 빠진 표시 순서를 외부 조회 전에 거절한다")
    void rejectsInvalidScoresAndOrder() {
        var invalid
            = List
                .of(
                    request(List.of(new Problem("1", "0.00", 1))),
                    request(List.of(new Problem("1", "9999.99", 1), new Problem("2", "0.01", 2))),
                    request(List.of(new Problem("1", "50.00", 1), new Problem("2", "50.00", 1)))
                );
        for (var item : invalid) {
            assertThatThrownBy(() -> service.create("12", "7", item))
                .isInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
                );
        }
        verifyNoInteractions(dependencies, members, transactions);
    }

    @Test
    @DisplayName("시작 시각이 서버 시각과 같으면 거절한다")
    void rejectsCurrentStart() {
        var request
            = new ExamCreateRequest(
                "시험",
                "FIXED",
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-01-01T01:30:00Z"),
                List.of(new Problem("1", "100.00", 1))
            );
        assertThatThrownBy(() -> service.create("12", "7", request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        verifyNoInteractions(dependencies, members, transactions);
    }

    @Test
    @DisplayName("마이크로초로 저장하면 현재와 같아지는 시작 시각은 거절한다")
    void rejectsStartLostByDatabasePrecision() {
        var request
            = new ExamCreateRequest(
                "시험",
                "FIXED",
                OffsetDateTime.parse("2026-01-01T00:00:00.000000001Z"),
                OffsetDateTime.parse("2026-01-01T01:00:00.000000001Z"),
                List.of(new Problem("1", "100.00", 1))
            );
        assertThatThrownBy(() -> service.create("12", "7", request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        verifyNoInteractions(dependencies, members, transactions);
    }

    /** 시작이 저장 범위 안이어도 종료를 서울 시간으로 저장할 수 없으면 요청을 거절한다. */
    @Test
    @DisplayName("서울 시간으로 바꾼 시험 종료가 9999년을 넘으면 거절한다")
    void rejectsEndOutsideDatabaseRange() {
        var request
            = new ExamCreateRequest(
                "시험",
                "FIXED",
                OffsetDateTime.parse("9999-12-31T13:30:00Z"),
                OffsetDateTime.parse("9999-12-31T15:30:00Z"),
                List.of(new Problem("1", "100.00", 1))
            );
        assertThatThrownBy(() -> service.create("12", "7", request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        verifyNoInteractions(dependencies, members, transactions);
    }

    @Test
    @DisplayName("종료된 스터디는 문제 조회와 저장을 수행하지 않는다")
    void rejectsClosedStudy() {
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12)).willReturn(new StudyMembers("12", "CLOSED", List.of()));
        assertThatThrownBy(() -> service.create("12", "7", request(List.of(new Problem("1", "100.00", 1)))))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_STUDY_CLOSED)
            );
        verifyNoInteractions(transactions);
    }

    @Test
    @DisplayName("일반 구성원은 시험을 생성할 수 없다")
    void rejectsOrdinaryMember() {
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12))
            .willReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "8", "MEMBER"))));
        assertThatThrownBy(() -> service.create("12", "7", request(List.of(new Problem("1", "100.00", 1)))))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
        verifyNoInteractions(transactions);
    }

    @Test
    @DisplayName("현재 스터디 관리자와 공개 문제를 확인하고 회차와 배점을 고정한다")
    void pinsAvailableRevision() {
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12))
            .willReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "8", "LEADER"))));
        given(dependencies.problems(List.of(1L)))
            .willReturn(
                new ProblemSummaries(
                    List.of(new ProblemSummary("1", true, "GENERAL", "PUBLIC", 3L, "10", 2, "문제", List.of("java")))
                )
            );
        given(guards.lockAll(any()))
            .willReturn(
                List
                    .of(
                        new LockedGuard(new Resource("PROBLEM", 1), "OPEN"),
                        new LockedGuard(new Resource("STUDY", 12), "OPEN")
                    )
            );
        given(jdbc.queryForMap(any(String.class), any(Object.class)))
            .willReturn(
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
        given(exams.saveAndFlush(any(Exam.class))).willAnswer(invocation -> {
            Exam exam = invocation.getArgument(0);
            ReflectionTestUtils.setField(exam, "id", 55L);
            return exam;
        });
        given(transactions.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        var result = service.create("12", "7", request(List.of(new Problem("1", "100.00", 1))));
        assertThat(result.examId()).isEqualTo("55");
        assertThat(result.totalScore()).isEqualTo("100.00");
        assertThat(result.problems().getFirst().problemRevisionId()).isEqualTo("10");
        verify(problems).saveAll(any());
    }

    @Test
    @DisplayName("조회 뒤 문제가 비공개로 바뀐 가드 상태면 시험을 저장하지 않는다")
    void rejectsProblemHiddenAfterLookup() {
        given(members.refresh(7)).willReturn("ACTIVE");
        given(dependencies.study(12))
            .willReturn(new StudyMembers("12", "ACTIVE", List.of(new StudyMember("7", "8", "LEADER"))));
        given(dependencies.problems(List.of(1L)))
            .willReturn(
                new ProblemSummaries(
                    List.of(new ProblemSummary("1", true, "GENERAL", "PUBLIC", 3L, "10", 2, "문제", List.of("java")))
                )
            );
        given(guards.lockAll(any()))
            .willReturn(
                List
                    .of(
                        new LockedGuard(new Resource("PROBLEM", 1), "BLOCKED"),
                        new LockedGuard(new Resource("STUDY", 12), "OPEN")
                    )
            );
        given(jdbc.queryForMap(any(String.class), any(Object.class)))
            .willReturn(
                Map
                    .of(
                        "state",
                        "BLOCKED",
                        "source_version",
                        4L,
                        "source_revision_id",
                        10L,
                        "source_scope",
                        "GENERAL",
                        "source_status",
                        "PRIVATE"
                    )
            );
        given(transactions.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        assertThatThrownBy(() -> service.create("12", "7", request(List.of(new Problem("1", "100.00", 1)))))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_PROBLEM_NOT_AVAILABLE)
            );
        verifyNoInteractions(exams, problems);
    }

    private ExamCreateRequest request(List<Problem> selected) {
        return new ExamCreateRequest(
            "시험",
            "FIXED",
            OffsetDateTime.parse("2026-01-01T01:00:00Z"),
            OffsetDateTime.parse("2026-01-01T02:30:00Z"),
            selected
        );
    }
}
