package com.backtoback.study.membership.domain;

/**
 * 스터디 가입 상태 (ERD 4.5.2 study_memberships.status, 정책 및 상태 §10 주요 상태 전이).
 */
public enum MembershipStatus {
    PENDING, APPROVED, REJECTED, CANCELED, EXPIRED, LEFT, REMOVED
}
