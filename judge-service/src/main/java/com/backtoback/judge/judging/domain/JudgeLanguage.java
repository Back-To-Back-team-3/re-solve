package com.backtoback.judge.judging.domain;

import java.util.regex.Pattern;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;

import lombok.Getter;

/**
 * 서비스 언어 코드와 실행 환경 선택 범위를 정의한다. (API 명세서 3.1.1)
 */
@Getter
public enum JudgeLanguage {
    JAVA("java", "Java 17", "Java \\((?:OpenJDK|JDK) (17(?:\\.[0-9]+)*)\\)"),
    PYTHON3("python3", "Python 3", "Python \\((3(?:\\.[0-9]+)*)\\)"),
    CPP("cpp", "C++17", "C\\+\\+ \\(GCC ([0-9]+(?:\\.[0-9]+)*)\\)");

    private final String code;
    private final String displayName;
    // 첫 번째 캡처 그룹은 서비스에서 숫자로 비교할 정식 버전이다.
    private final Pattern runtimePattern;

    /**
     * 지원 언어의 API 코드·표시 이름·실행 환경 선택 패턴을 정의한다.
     *
     * @param code API와 설정에서 사용하는 언어 코드
     * @param displayName 사용자에게 표시할 이름
     * @param runtimePattern 정식 버전을 첫 번째 그룹으로 추출하는 정규식
     */
    JudgeLanguage(String code, String displayName, String runtimePattern) {
        this.code = code;
        this.displayName = displayName;
        this.runtimePattern = Pattern.compile(runtimePattern);
    }

    /**
     * 외부 언어 코드를 지원 언어로 변환한다.
     * 기존 API 계약에 따라 대소문자나 앞뒤 공백을 보정하지 않는다.
     *
     * @param code {@code java}, {@code python3} 또는 {@code cpp}
     * @return 일치하는 지원 언어
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 코드가 {@code null}이거나 지원하지 않는 경우
     */
    public static JudgeLanguage fromCode(String code) {
        for (JudgeLanguage language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        throw new JudgeException(JudgeErrorCode.UNSUPPORTED_LANGUAGE);
    }
}
