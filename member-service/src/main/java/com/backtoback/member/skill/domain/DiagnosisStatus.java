package com.backtoback.member.skill.domain;

/**
 * 진단 응시 상태 (ERD 4.1.6). {@code IN_PROGRESS → CLOSED → SCORED}.
 */
public enum DiagnosisStatus {
    IN_PROGRESS, CLOSED, SCORED
}
