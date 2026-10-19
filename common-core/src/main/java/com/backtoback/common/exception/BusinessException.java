package com.backtoback.common.exception;

import org.springframework.http.HttpHeaders;

import lombok.Getter;

/**
 * 서비스별 전용 예외가 공통 응답 처리에 전달할 오류 코드와 부가 응답 헤더를 보관한다.
 * 공통 코드와 도메인 코드 모두 받을 수 있으며 HTTP 응답 생성은 웹 모듈에서 담당한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 코드와 사용자 안내의 전달</li>
 * <li>API 명세서 v1.1 / §1.5.1 진단 시작: Retry-After를 포함한 오류 응답 지원</li>
 * </ul>
 */
@Getter
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    // 호출자가 계산한 Retry-After 등 응답 헤더의 읽기 전용 복사본
    private final HttpHeaders responseHeaders;

    /**
     * 전용 예외의 코드와 안전한 기본 메시지를 설정한다.
     *
     * @param errorCode 공통 또는 도메인 오류 코드
     */
    protected BusinessException(ErrorCode errorCode) {
        this(errorCode, HttpHeaders.EMPTY);
    }

    /**
     * 전용 예외의 코드와 서비스가 계산한 부가 응답 헤더를 설정한다.
     * 헤더는 복사해 보관하며 대기 시간 계산이나 요청 헤더 전달은 수행하지 않는다.
     *
     * @param errorCode 공통 또는 도메인 오류 코드
     * @param responseHeaders 공개할 응답 헤더; 없으면 빈 헤더이며 null은 허용하지 않음
     */
    protected BusinessException(ErrorCode errorCode, HttpHeaders responseHeaders) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.responseHeaders = HttpHeaders.readOnlyHttpHeaders(HttpHeaders.copyOf(responseHeaders));
    }
}
