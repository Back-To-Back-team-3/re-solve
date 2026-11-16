package com.backtoback.judge.run.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.run.client.ExamRunContextClient;
import com.backtoback.judge.run.dto.ExamRunRequest;
import com.backtoback.judge.run.dto.ExamRunResponse;
import com.backtoback.judge.run.dto.RunRequest;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;

import lombok.RequiredArgsConstructor;

/** 시험·진단의 고정 회차를 일반 Run과 같은 실행 제한·기한으로 실행한다(API 명세서 §3.3.8). */
@Service
@RequiredArgsConstructor
public class ExamRunService {
    private final RunService runs;
    private final ExamRunContextClient contexts;
    private final Clock clock;

    /**
     * 호출 서비스가 확인한 시험·진단 고정 회차를 실행한다.
     *
     * @param request 문맥·회원·고정 회차·실행 코드
     * @return 저장하지 않는 공개 예제·직접 입력 결과
     * @throws JudgeException 문맥·코드·입력·실행 환경 검증 또는 실행에 실패한 경우
     */
    public ExamRunResponse run(ExamRunRequest request) {
        if (!("EXAM".equals(request.context()) || "DIAGNOSIS".equals(request.context())) || request.sourceCode() == null
            || request.sourceCode().isBlank() || request.customTests() != null && request.customTests().size() > 10) {
            throw new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        RunRequest.validateSourceSize(request.sourceCode());
        SubmissionCreateRequest.parseId(request.contextId());
        long memberId = SubmissionCreateRequest.parseId(request.memberId());
        long problemId = SubmissionCreateRequest.parseId(request.problemId());
        long revisionId = SubmissionCreateRequest.parseId(request.problemRevisionId());
        var language = JudgeLanguage.fromCode(request.language());
        try {
            var response = runs.runExam(memberId, problemId, revisionId, language, request.sourceCode(), () -> {
                // 진단 권한은 Member가 §1.5.9에서 확인하며 Contest에 재검증하지 않는다.
                if ("EXAM".equals(request.context())) {
                    contexts.check(request);
                }
            }, data -> customInputs(request.customTests(), data.functionSpec()));
            return ExamRunResponse.from(response, clock.instant());
        } catch (JudgeException failure) {
            if (failure.getErrorCode() == JudgeErrorCode.RUN_RATE_LIMITED) {
                throw new JudgeException(JudgeErrorCode.RUN_IN_PROGRESS);
            }
            throw failure;
        }
    }

    /**
     * 위치 기반 인수를 함수 매개변수 이름에 연결하고 JSON 원문을 유지한다.
     *
     * @param tests 직접 입력 목록
     * @param function 고정 회차의 함수 명세
     * @return 매개변수 이름에 대응하는 JSON 원문
     * @throws JudgeException 인수 개수가 명세와 다른 경우
     */
    private List<RunRequest.CustomInput> customInputs(List<ExamRunRequest.CustomTest> tests, FunctionSpec function) {
        if (tests == null) {
            return List.of();
        }
        var inputs = new ArrayList<RunRequest.CustomInput>();
        var parameters = function.parameters();
        for (var test : tests) {
            if (test == null || test.args() == null || test.args().size() != parameters.size()) {
                throw new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
            var arguments = new HashMap<String, String>();
            for (int index = 0; index < parameters.size(); index++) {
                var argument = test.args().get(index);
                arguments.put(parameters.get(index).name(), argument == null ? "null" : argument.toString());
            }
            inputs.add(new RunRequest.CustomInput(arguments));
        }
        return inputs;
    }

}
