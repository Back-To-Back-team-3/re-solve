package com.backtoback.problem.global.security;

import java.security.Principal;

public record GatewayUser(String userId, String role) implements Principal {
    @Override
    public String getName() {
        return userId;
    }
}
