package com.backtoback.judge.judging.service;

import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.FunctionSpecValidator;

import lombok.RequiredArgsConstructor;

/** 저장·외부 호출 없이 기존 언어별 생성기로 기본 코드를 생성한다. */
@Service
@RequiredArgsConstructor
public class StarterCodeService {
    private final List<ExecutionCodeGenerator> generators;

    /**
     * 함수 명세와 언어의 중복·지원 여부를 검사하고 요청 순서로 생성한다.
     *
     * @param spec 고정 함수 명세
     * @param languages 통과 언어
     * @return 요청 언어와 정확히 일치하는 생성 결과
     * @throws JudgeException 잘못된 명세·언어이면 {@code COMMON_INVALID_REQUEST}
     */
    public List<Code> generate(FunctionSpec spec, List<String> languages) {
        try {
            new FunctionSpecValidator().validate(spec);
            if (languages == null || new HashSet<>(languages).size() != languages.size()) {
                throw new IllegalArgumentException("중복 없는 언어 목록이 필요합니다.");
            }
            return languages.stream().map(language -> {
                var generator
                    = generators
                        .stream()
                        .filter(value -> value.language().getCode().equals(language))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 언어입니다."));
                return new Code(language, generator.starterCode(spec));
            }).toList();
        } catch (IllegalArgumentException failure) {
            var error = new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
            error.initCause(failure);
            throw error;
        }
    }

    /** 요청 언어와 생성된 기본 코드. */
    public record Code(String language, String sourceCode) {
    }
}
