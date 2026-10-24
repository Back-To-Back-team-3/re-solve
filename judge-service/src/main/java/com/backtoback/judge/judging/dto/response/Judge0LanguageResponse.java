package com.backtoback.judge.judging.dto.response;

/**
 * Judge0 언어 목록 응답의 실행 환경 ID와 이름을 담는다.
 *
 * @param id Judge0 실행 환경 ID
 * @param name 언어·컴파일러·버전이 포함된 실행 환경 이름
 */
public record Judge0LanguageResponse(Integer id, String name) {
}
