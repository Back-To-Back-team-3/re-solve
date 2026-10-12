package com.backtoback.problem.global.security.user;

import java.security.Principal;

public record GatewayUser(String userId, UserRole role) implements Principal {
    @Override
    public String getName() {
        return userId;
    }
}
