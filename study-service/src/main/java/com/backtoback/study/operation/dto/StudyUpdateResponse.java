package com.backtoback.study.operation.dto;

/**
 * 스터디 설정 수정 응답 바디 (API 명세서 §5.3.1 응답 바디).
 */
public record StudyUpdateResponse(String studyId, Long version) {
}
