package com.backtoback.common.web.security.user;

import java.security.Principal;

/**
 * Gateway가 전달한 사용자 ID와 역할을 인증 principal로 보관한다.
 * 헤더 검증은 인증 필터가 담당한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: Gateway 사용자 식별·역할 헤더</li>
 * <li>API 명세서 v1.1 / §0.2 식별자(ID) 규칙: 인증 정보에 의한 요청자 식별</li>
 * </ul>
 *
 * @param userId Gateway가 인증한 사용자 식별 문자열
 * @param role 서비스 공통 사용자 역할
 */
public record GatewayUser(String userId, UserRole role) implements Principal {
    /**
     * 인증 principal의 이름으로 사용자 ID를 반환한다.
     *
     * @return Gateway에서 전달한 사용자 ID
     */
    @Override
    public String getName() {
        return userId;
    }
}
