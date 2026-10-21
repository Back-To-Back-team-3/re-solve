package com.backtoback.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * 여러 서비스에서 동일한 의미로 사용하는 요청·인증·의존성 오류를 정의한다.
 * OAuth·Refresh Token 등 특정 인증 기능의 오류는 소유 서비스에서 정의한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: 멱등키 충돌의 422 응답</li>
 * <li>API 명세서 v1.1 / §0.4 에러 코드 표: Common · Auth의 공통 오류와 HTTP 상태</li>
 * </ul>
 */
@Getter
public enum CommonErrorCode implements ErrorCode {
    // 요청 파라미터 또는 본문 검증 실패
    COMMON_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청을 확인해 주세요."),
    // 예상하지 못한 서버 내부 오류
    COMMON_INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    // 검증에 필요한 내부 서비스 조회 실패
    COMMON_DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "필요한 서비스를 조회할 수 없습니다."),
    // 같은 멱등키에 다른 요청 본문 전달
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.UNPROCESSABLE_CONTENT, "같은 멱등키로 다른 요청을 보낼 수 없습니다."),
    // 인증 정보가 없거나 유효하지 않음
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "인증 정보가 없거나 유효하지 않습니다."),
    // Access Token의 유효 기간 만료
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증 토큰이 만료되었습니다."),
    // 인증된 요청자의 리소스 접근 권한 부족
    AUTH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");

    private final HttpStatus status;
    private final String message;

    /**
     * 오류 상태와 사용자에게 공개할 기본 메시지를 연결한다.
     *
     * @param status 문서에 정의된 HTTP 상태
     * @param message 기본 사용자 안내
     */
    CommonErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * enum 상수 이름을 JSON 오류 코드로 사용한다.
     *
     * @return 클라이언트 분기에 사용하는 상수 이름
     */
    @Override
    public String getCode() {
        return name();
    }
}
