package com.backtoback.judge.global.security.user;

import java.security.Principal;

public record GatewayUser(String userId, UserRole role) implements Principal {
    /**
     * {@inheritDoc}
     * Gateway가 전달한 사용자 ID를 principal 이름으로 반환한다.
     */
    @Override
    public String getName() {
        return userId;
    }
}
