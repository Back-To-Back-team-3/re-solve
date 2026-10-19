package com.backtoback.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 공통 코드와 서비스별 코드가 동일한 예외·응답 처리에 참여하기 위한 규격이다.
 * 각 서비스의 enum이 구현하며, 도메인 오류를 공통 모듈에 모으지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: error.code와 error.message</li>
 * <li>API 명세서 v1.1 / §0.4 에러 코드 표: 오류별 HTTP 상태</li>
 * </ul>
 */
public interface ErrorCode {
    /**
     * 응답과 클라이언트 분기에 사용하는 오류 문자열을 반환한다.
     *
     * @return 문서의 오류 코드; enum 구현은 상수 이름 사용
     */
    String getCode();

    /**
     * 오류 응답에 적용할 HTTP 상태를 반환한다.
     *
     * @return 해당 오류의 HTTP 상태
     */
    HttpStatus getStatus();

    /**
     * 내부 예외 내용과 구분한 기본 사용자 안내를 반환한다.
     *
     * @return 사용자에게 공개할 기본 메시지
     */
    String getMessage();
}
