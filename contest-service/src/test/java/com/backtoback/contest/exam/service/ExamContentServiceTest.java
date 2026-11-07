package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.contest.exam.client.ExamContentClient;
import com.backtoback.contest.exam.client.ExamContentClient.Content;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamProblem;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 시험 입장과 고정 회차를 확인한 뒤 공개 본문만 변환한다. */
@ExtendWith(MockitoExtension.class)
class ExamContentServiceTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamProblemRepository problems;
    @Mock
    private ExamParticipantRepository participants;
    @Mock
    private ExamContentClient client;
    @Mock
    private TransactionTemplate transactions;
    private ExamContentService service;
    private ExamParticipant participant;

    /** 고정 문제와 서버 시간을 준비한다. */
    @BeforeEach
    void setUp() {
        service
            = new ExamContentService(
                exams,
                problems,
                participants,
                client,
                transactions,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
            );
        var exam
            = Exam
                .create(
                    12L,
                    7L,
                    "시험",
                    ExamMode.FIXED,
                    LocalDateTime.parse("2026-01-01T08:00:00"),
                    LocalDateTime.parse("2026-01-01T10:00:00"),
                    null,
                    1
                );
        participant = ExamParticipant.register(exam, 7, LocalDateTime.parse("2026-01-01T07:00:00"));
        ReflectionTestUtils.setField(participant, "id", 411L);
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        given(transactions.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    /** 본문의 회차와 이미지 경로는 시험에 고정된 값을 사용한다. */
    @Test
    @DisplayName("입장한 참가자에게 고정 본문과 시험 이미지 경로를 반환한다")
    void returnsPinnedContent() {
        // given
        participant.enter(LocalDateTime.parse("2026-01-01T08:00:00"));
        var problem = ExamProblem.pin(participant.getExam(), 101L, 1001L, "제목", 2, new BigDecimal("100.00"), 1);
        given(problems.findByExamIdOrderByDisplayOrderAsc(55L)).willReturn(List.of(problem));
        given(client.content(55, 101, 1001))
            .willReturn(
                new Content(
                    "101",
                    "1001",
                    2,
                    "제목",
                    "본문 image:702 및 [링크](image:703), ![그림](image:701)",
                    "제한 `![코드](image:704)`",
                    "예시",
                    new ExamContentClient.FunctionSpec(
                        "solution",
                        List.of(),
                        new ExamContentClient.TypeSpec("INT32", null, null, null, null)
                    ),
                    new ExamContentClient.ExecutionLimits(1000, 1024, 6000, 64),
                    List.of()
                )
            );
        // when
        var response = service.read("55", "7");
        // then
        assertThat(response.problems()).hasSize(1);
        assertThat(response.problems().getFirst().problemRevisionId()).isEqualTo("1001");
        assertThat(response.problems().getFirst().imageIds()).containsExactly("701");
        assertThat(response.problems().getFirst().body())
            .contains("![그림](/api/v1/exams/55/problems/101/images/701)")
            .contains("image:702", "[링크](image:703)", "`![코드](image:704)`");
    }

    /** 등록만 한 회원은 Problem에 요청을 보내기 전에 차단한다. */
    @Test
    @DisplayName("실제 입장하지 않은 등록자는 본문을 열람하지 못한다")
    void rejectsUnentered() {
        // when & then
        assertThatThrownBy(() -> service.read("55", "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_NOT_REGISTERED)
            );
        verifyNoInteractions(client);
    }

    /** 고정 본문에 없는 이미지는 이미지 저장소 조회 전에 거절한다. */
    @Test
    @DisplayName("고정 본문에 없는 이미지 ID는 Problem 이미지 조회 없이 거절한다")
    void rejectsUnlinkedImage() {
        // given
        participant.enter(LocalDateTime.parse("2026-01-01T08:00:00"));
        var problem = ExamProblem.pin(participant.getExam(), 101L, 1001L, "제목", 2, new BigDecimal("100.00"), 1);
        given(problems.findByExamIdOrderByDisplayOrderAsc(55L)).willReturn(List.of(problem));
        given(client.content(55, 101, 1001))
            .willReturn(
                new Content(
                    "101",
                    "1001",
                    2,
                    "제목",
                    "본문 ![그림](image:701)",
                    "제한",
                    "예시",
                    new ExamContentClient.FunctionSpec(
                        "solution",
                        List.of(),
                        new ExamContentClient.TypeSpec("INT32", null, null, null, null)
                    ),
                    new ExamContentClient.ExecutionLimits(1000, 1024, 6000, 64),
                    List.of()
                )
            );
        // when & then
        assertThatThrownBy(() -> service.image("55", "7", "101", "702"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.PROBLEM_NOT_FOUND)
            );
        verify(client, never()).image(55, 101, 1001, 702);
    }

    /** 시험에 포함되지 않은 문제의 이미지는 문제 없음으로 처리한다. */
    @Test
    @DisplayName("시험에 없는 문제의 이미지는 404로 거절한다")
    void rejectsImageOutsideExam() {
        // given
        participant.enter(LocalDateTime.parse("2026-01-01T08:00:00"));
        given(problems.findByExamIdOrderByDisplayOrderAsc(55L)).willReturn(List.of());
        // when & then
        assertThatThrownBy(() -> service.image("55", "7", "101", "701"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.PROBLEM_NOT_FOUND)
            );
        verifyNoInteractions(client);
    }
}
