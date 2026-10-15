package com.backtoback.common.exception;

import lombok.Getter;

/**
 * 서비스별 전용 예외가 공통 응답 처리에 전달할 오류 코드를 보관한다.
 * 공통 코드와 도메인 코드 모두 받을 수 있으며 HTTP 응답 생성은 웹 모듈에서 담당한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 코드와 사용자 안내의 전달</li>
 * </ul>
 */
@Getter
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    /**
     * 전용 예외의 코드와 안전한 기본 메시지를 설정한다.
     *
     * @param errorCode 공통 또는 도메인 오류 코드
     */
    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
