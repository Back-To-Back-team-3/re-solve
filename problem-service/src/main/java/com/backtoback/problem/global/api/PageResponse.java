package com.backtoback.problem.global.api;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {
    /**
     * 조회 결과를 공통 페이지 응답으로 변환한다.
     *
     * @param <T> 목록 항목의 타입
     * @param result 응답할 목록 항목이 담긴 조회 페이지
     * @return 목록 항목과 페이지 정보
     */
    public static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages(),
            result.hasNext()
        );
    }
}
