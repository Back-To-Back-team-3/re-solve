package com.backtoback.study.assignment.dto;

/**
 * 문제집 생성 응답 바디 (API 명세서 §5.4.1).
 */
public record AssignmentCreateResponse(String assignmentId, String status) {

}
