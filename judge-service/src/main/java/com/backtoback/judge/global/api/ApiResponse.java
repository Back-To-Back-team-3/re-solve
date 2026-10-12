package com.backtoback.judge.global.api;

public record ApiResponse<T>(boolean success, String message, T data) {
    /**
     * 공통 성공 응답 형식 (API 명세서 0.3).
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, null, data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
}
