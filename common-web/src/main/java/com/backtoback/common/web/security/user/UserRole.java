package com.backtoback.common.web.security.user;

/**
 * Gateway가 전달하는 서비스 공통 사용자 역할을 정의한다.
 * 스터디 내 역할이나 리소스 소유권은 각 서비스에서 별도로 확인한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.5 상태값 요약: 회원 role의 USER·ADMIN 구분</li>
 * </ul>
 */
public enum UserRole {
    // 일반 사용자이며 리소스별 권한은 소유 서비스가 확인
    USER,
    // 관리자 API에 접근할 수 있는 역할이며 리소스별 추가 조건은 별도 확인
    ADMIN
}
