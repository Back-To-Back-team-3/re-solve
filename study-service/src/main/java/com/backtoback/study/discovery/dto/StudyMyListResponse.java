package com.backtoback.study.discovery.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 내 스터디 목록 응답 (API 명세서 5.1.3). 공통 {@code PageResponse}에 참여 카운터 2개를 더한 전용 응답이라
 * 별도 타입으로 둔다(공통 모듈 {@code PageResponse}는 필드가 고정된 record).
 */
public record StudyMyListResponse(
    List<StudyMySummaryResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext,
    int activeJoinCount,
    int activeJoinLimit
) {

    public static StudyMyListResponse of(
        Page<StudyMySummaryResponse> result,
        int activeJoinCount,
        int activeJoinLimit
    ) {
        return new StudyMyListResponse(
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages(),
            result.hasNext(),
            activeJoinCount,
            activeJoinLimit
        );
    }
}
