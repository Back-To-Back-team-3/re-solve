package com.backtoback.common.api;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 페이지 조회 결과를 서비스에 관계없이 같은 목록 구조로 제공한다.
 * 요청의 페이지 번호와 크기 제한은 각 API에서 검증하며 이 타입은 조회 결과만 변환한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: 0부터 시작하는 페이지와 목록 응답 필드</li>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 목록 조회 성공의 data 구조</li>
 * </ul>
 *
 * @param <T> 목록 항목 타입
 * @param content 현재 페이지의 항목
 * @param page 0부터 시작하는 현재 페이지 번호
 * @param size 페이지 크기
 * @param totalElements 전체 항목 수; 식별자가 아니므로 JSON 숫자로 표현
 * @param totalPages 전체 페이지 수
 * @param hasNext 다음 페이지 존재 여부
 */
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {
    /**
     * Spring Data 페이지의 항목과 메타데이터를 공통 응답에 옮긴다.
     *
     * @param <T> 목록 항목 타입
     * @param result 조회 페이지
     * @return 원래 페이지 정보를 유지한 목록 응답
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
