package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.service.MemberService;
import com.backtoback.member.skill.client.JudgeDiagnosisRunClient;
import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisProblem;
import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.repository.DiagnosisAttemptRepository;
import com.backtoback.member.skill.repository.DiagnosisProblemRepository;

/** 서버 종료 시각의 정확한 경계와 실행 입력 상한을 확인한다. */
@ExtendWith(MockitoExtension.class)
class DiagnosisRunServiceTest {
    @Mock
    private MemberService members;
    @Mock
    private DiagnosisAttemptRepository attempts;
    @Mock
    private DiagnosisProblemRepository problems;
    @Mock
    private JudgeDiagnosisRunClient judge;
    @Mock
    private PlatformTransactionManager transactions;
    @Mock
    private Member member;
    @Mock
    private DiagnosisAttempt attempt;
    @Mock
    private DiagnosisProblem problem;
    private DiagnosisRunService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

    /** 각 테스트에 같은 서버 시각과 의존성을 주입한다. */
    @BeforeEach
    void setUp() {
        service
            = new DiagnosisRunService(members, attempts, problems, judge, new TransactionTemplate(transactions), clock);
    }

    /** 종료 시각과 같은 순간에는 신규 Run을 접수하지 않는다. */
    @Test
    @DisplayName("서버 시각이 종료 시각과 같으면 Run을 거절한다")
    void rejectsAtExactDeadline() {
        given(transactions.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(members.getMember(7L)).willReturn(member);
        given(attempts.findByIdAndMemberId(120L, 7L)).willReturn(Optional.of(attempt));
        given(problems.findAllByDiagnosisAttemptIdOrderByDifficultyAsc(120L)).willReturn(List.of(problem));
        given(problem.getProblemId()).willReturn(301L);
        given(attempt.getStatus()).willReturn(DiagnosisStatus.IN_PROGRESS);
        given(attempt.getEndsAt()).willReturn(LocalDateTime.now(clock));

        assertThatThrownBy(
            () -> service.run(7L, 120L, 301L, new DiagnosisRunService.Command("java", "code", List.of()))
        )
            .isInstanceOfSatisfying(
                MemberException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.DIAGNOSIS_CLOSED)
            );
        then(judge).shouldHaveNoInteractions();
    }

    /** 잘못된 입력은 권한 조회와 원격 실행 이전에 거절한다. */
    @Test
    @DisplayName("빈 코드·지원하지 않는 언어·UTF-8 코드 상한 초과를 실행하지 않는다")
    void rejectsInvalidCode() {
        assertThatThrownBy(() -> service.run(7L, 120L, 301L, new DiagnosisRunService.Command("java", " ", List.of())))
            .isInstanceOfSatisfying(
                MemberException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        assertThatThrownBy(
            () -> service.run(7L, 120L, 301L, new DiagnosisRunService.Command("ruby", "code", List.of()))
        )
            .isInstanceOfSatisfying(
                MemberException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.UNSUPPORTED_LANGUAGE)
            );
        assertThatThrownBy(
            () -> service.run(7L, 120L, 301L, new DiagnosisRunService.Command("java", "가".repeat(22000), List.of()))
        )
            .isInstanceOfSatisfying(
                MemberException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.CODE_TOO_LARGE)
            );
        then(judge).shouldHaveNoInteractions();
        then(transactions).shouldHaveNoInteractions();
    }
}
