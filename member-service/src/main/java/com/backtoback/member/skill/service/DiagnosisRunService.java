package com.backtoback.member.skill.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.service.MemberService;
import com.backtoback.member.skill.client.JudgeDiagnosisRunClient;
import com.backtoback.member.skill.client.JudgeDiagnosisRunClient.CustomTest;
import com.backtoback.member.skill.client.JudgeDiagnosisRunClient.RunRequest;
import com.backtoback.member.skill.client.JudgeDiagnosisRunClient.RunResult;
import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.repository.DiagnosisAttemptRepository;
import com.backtoback.member.skill.repository.DiagnosisProblemRepository;

import lombok.RequiredArgsConstructor;

/** 진단의 실행 권한과 고정 회차를 조회하고 저장 없는 Run을 중계한다(API 명세서 §1.5.9). */
@Service
@RequiredArgsConstructor
public class DiagnosisRunService {
    private final MemberService members;
    private final DiagnosisAttemptRepository attempts;
    private final DiagnosisProblemRepository problems;
    private final JudgeDiagnosisRunClient judge;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 본인의 진행 중 진단에 출제된 회차를 실행한다. DB 연결은 원격 실행 전에 반환한다.
     *
     * @param memberId 인증된 회원 ID
     * @param diagnosisId 진단 ID
     * @param problemId 출제 문항 ID
     * @param command 실행 코드와 직접 입력
     * @return 점수·레벨에 반영하지 않는 실행 결과
     * @throws MemberException 권한·시간·코드·입력이 유효하지 않거나 실행에 실패한 경우
     */
    public RunResult run(Long memberId, Long diagnosisId, Long problemId, Command command) {
        validate(command);
        // Run은 상태를 변경하지 않으며 실행 중에는 DB 트랜잭션을 유지하지 않는다.
        var readOnly = new TransactionTemplate(transactions.getTransactionManager());
        readOnly.setReadOnly(true);
        RunRequest request = readOnly.execute(status -> authorize(memberId, diagnosisId, problemId, command));
        return judge.run(request);
    }

    /**
     * 실행 시점의 회원·응시 상태와 불변 회차를 확인한다.
     *
     * @param memberId 인증된 회원 ID
     * @param diagnosisId 진단 ID
     * @param problemId 출제 문항 ID
     * @param command 실행 코드와 직접 입력
     * @return 내부 호출에 사용할 고정 회차 요청
     * @throws MemberException 타인·미출제·종료·만료·비활성 회원인 경우
     */
    private RunRequest authorize(Long memberId, Long diagnosisId, Long problemId, Command command) {
        var member = members.getMember(memberId);
        if (member.isSuspended()) {
            throw new MemberException(MemberErrorCode.MEMBER_SUSPENDED);
        }
        if (member.isWithdrawn()) {
            throw new MemberException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var attempt
            = attempts
                .findByIdAndMemberId(diagnosisId, memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.DIAGNOSIS_NOT_FOUND));
        var problem
            = problems
                .findAllByDiagnosisAttemptIdOrderByDifficultyAsc(diagnosisId)
                .stream()
                .filter(item -> item.getProblemId().equals(problemId))
                .findFirst()
                .orElseThrow(() -> new MemberException(MemberErrorCode.DIAGNOSIS_NOT_FOUND));
        if (attempt.getStatus() != DiagnosisStatus.IN_PROGRESS
            || !LocalDateTime.now(clock).isBefore(attempt.getEndsAt())) {
            throw new MemberException(MemberErrorCode.DIAGNOSIS_CLOSED);
        }
        return new RunRequest(
            "DIAGNOSIS",
            diagnosisId.toString(),
            memberId.toString(),
            problemId.toString(),
            problem.getProblemRevisionId().toString(),
            command.language(),
            command.sourceCode(),
            command.customTests()
        );
    }

    /**
     * 기존 진단 코드 상한·지원 언어·직접 입력 개수를 검사한다.
     *
     * @param command 실행 코드와 직접 입력
     * @throws MemberException 실행 요청이 문서 제한을 벗어난 경우
     */
    private void validate(Command command) {
        if (command.sourceCode() == null || command.sourceCode().isBlank() || command.customTests() == null
            || command.customTests().size() > 10
            || command.customTests().stream().anyMatch(test -> test == null || test.args() == null)) {
            throw new MemberException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        if (command.language() == null || !Set.of("java", "python3", "cpp").contains(command.language())) {
            throw new MemberException(MemberErrorCode.UNSUPPORTED_LANGUAGE);
        }
        if (command.sourceCode().getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new MemberException(MemberErrorCode.CODE_TOO_LARGE);
        }
    }

    /** 실행에 필요한 코드와 위치 기반 인수를 묶은 명령이다. */
    public record Command(String language, String sourceCode, List<CustomTest> customTests) {
    }
}
