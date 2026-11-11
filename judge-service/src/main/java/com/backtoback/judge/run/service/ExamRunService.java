package com.backtoback.judge.run.service;

import java.math.RoundingMode;
import java.time.Instant;
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
import com.backtoback.judge.run.dto.RunResponse;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;

import lombok.RequiredArgsConstructor;

/** 시험 문맥을 검증하고 일반 Run의 실행 경로를 내부 계약에 맞춰 사용한다. */
@Service
@RequiredArgsConstructor
public class ExamRunService {
    private final RunService runs;
    private final ExamRunContextClient contexts;

    /** 시험의 고정 회차만 실행하고 Contest가 읽는 결과로 변환한다. */
    public ExamRunResponse run(ExamRunRequest request) {
        if (!"EXAM".equals(request.context()) || request.sourceCode() == null || request.sourceCode().isBlank()
            || request.customTests() != null && request.customTests().size() > 10) {
            throw new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        RunRequest.validateSourceSize(request.sourceCode());
        SubmissionCreateRequest.parseId(request.contextId());
        long memberId = SubmissionCreateRequest.parseId(request.memberId());
        long problemId = SubmissionCreateRequest.parseId(request.problemId());
        long revisionId = SubmissionCreateRequest.parseId(request.problemRevisionId());
        var language = JudgeLanguage.fromCode(request.language());
        try {
            var response
                = runs
                    .runExam(
                        memberId,
                        problemId,
                        revisionId,
                        language,
                        request.sourceCode(),
                        () -> contexts.check(request),
                        data -> customInputs(request.customTests(), data.functionSpec())
                    );
            return toResponse(response);
        } catch (JudgeException failure) {
            if (failure.getErrorCode() == JudgeErrorCode.RUN_RATE_LIMITED) {
                throw new JudgeException(JudgeErrorCode.RUN_IN_PROGRESS);
            }
            throw failure;
        }
    }

    /** 위치 기반 인수를 고정 함수 명세의 매개변수 이름에 연결하고 JSON 원문을 유지한다. */
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

    /** 공개 예제와 직접 입력의 순번은 각각 0부터 시작한다. */
    private ExamRunResponse toResponse(RunResponse response) {
        var items = new ArrayList<ExamRunResponse.Result>();
        int sampleIndex = 0;
        for (var item : response.results()) {
            int index = "SAMPLE".equals(item.source()) ? sampleIndex++ : item.index();
            int timeMs = item.timeMs() == null ? 0 : item.timeMs();
            int memoryKb
                = item.memoryKb() == null ? 0 : item.memoryKb().setScale(0, RoundingMode.CEILING).intValueExact();
            items
                .add(
                    new ExamRunResponse.Result(
                        item.source(),
                        index,
                        item.actualReturnText(),
                        error(item, response.compileMessage()),
                        timeMs,
                        memoryKb
                    )
                );
        }
        return new ExamRunResponse(List.copyOf(items), Instant.now());
    }

    /** 실행 실패는 오류로 전달하고 기대값 불일치는 출력 확인이 가능하도록 유지한다. */
    private String error(RunResponse.Result item, String compileMessage) {
        if (item.verdict() == null) {
            return null;
        }
        return switch (item.verdict()) {
            case AC, WA -> null;
            case CE -> compileMessage == null ? item.verdict().name() : compileMessage;
            default -> item.verdict().name();
        };
    }
}
