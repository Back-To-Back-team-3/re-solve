package com.backtoback.judge.judging.dto;

import java.util.List;

import com.backtoback.judge.judging.service.StarterCodeService;

/** 요청 언어별 기본 코드의 내부 직접 응답이다. */
public record StarterCodeResponse(List<Code> starterCodes) {
    /**
     * 생성 결과를 내부 API 응답으로 변환한다.
     *
     * @param codes 요청 순서의 생성 결과
     * @return 공통 래퍼 없는 기본 코드
     */
    public static StarterCodeResponse from(List<StarterCodeService.Code> codes) {
        return new StarterCodeResponse(
            codes.stream().map(code -> new Code(code.language(), code.sourceCode())).toList()
        );
    }

    /** 한 언어의 기본 코드. */
    public record Code(String language, String sourceCode) {
    }
}
