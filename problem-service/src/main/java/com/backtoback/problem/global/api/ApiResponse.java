package com.backtoback.problem.global.api;

public record ApiResponse<T>(boolean success, String message, T data) {
    /**
     * 처리 결과를 공통 성공 응답으로 감싼다.
     *
     * @param <T> 응답 데이터 타입
     * @param data 응답 데이터
     * @return 공통 형식의 성공 응답
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, null, data);
    }

    /**
     * 처리 결과를 공통 성공 응답으로 감싼다.
     *
     * @param <T> 응답 데이터 타입
     * @param message 사용자에게 전달할 메시지
     * @param data 응답 데이터
     * @return 공통 형식의 성공 응답
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
}
