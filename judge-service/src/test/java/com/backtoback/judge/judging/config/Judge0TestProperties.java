package com.backtoback.judge.judging.config;

import java.time.Duration;

/**
 * 외부 서버 없이 통신·언어 선택 계약을 검증할 설정을 생성한다.
 */
public final class Judge0TestProperties {
    /**
     * 테스트 설정 팩토리의 인스턴스 생성을 막는다.
     */
    private Judge0TestProperties() {}

    /**
     * 모든 언어를 활성화한 테스트용 통신 설정을 생성한다.
     *
     * @param baseUrl 모의 Judge0 주소
     * @return 테스트 설정
     */
    public static Judge0Properties properties(String baseUrl) {
        return properties(baseUrl, true, true, true);
    }

    /**
     * 언어별 활성화 여부를 지정한 테스트용 설정을 생성한다.
     *
     * @param baseUrl 모의 Judge0 주소
     * @param javaEnabled Java 허용 여부
     * @param pythonEnabled Python 허용 여부
     * @param cppEnabled C++ 허용 여부
     * @return 테스트 설정
     */
    public static Judge0Properties properties(
        String baseUrl,
        boolean javaEnabled,
        boolean pythonEnabled,
        boolean cppEnabled
    ) {
        return new Judge0Properties(
            baseUrl,
            "X-Auth-Token",
            "",
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            new Judge0Properties.Languages(
                new Judge0Properties.Language(javaEnabled),
                new Judge0Properties.Language(pythonEnabled),
                new Judge0Properties.Language(cppEnabled)
            )
        );
    }
}
