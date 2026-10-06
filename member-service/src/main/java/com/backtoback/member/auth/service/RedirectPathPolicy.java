package com.backtoback.member.auth.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.backtoback.member.auth.config.AuthProperties;

/**
 * 로그인 후 이동 경로는 허용 목록에 있는 같은 출처의 상대 경로만 받는다(오픈 리다이렉트 방지).
 */
@Component
public class RedirectPathPolicy {

    static final String DEFAULT_PATH = "/";
    private static final int MAX_LENGTH = 200;

    private final List<String> allowedPrefixes;

    public RedirectPathPolicy(AuthProperties authProperties) {
        this.allowedPrefixes = authProperties.allowedRedirectPaths();
    }

    public boolean isAllowed(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_LENGTH) {
            return false;
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.contains("..")) {
            return false;
        }
        if (path.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        return allowedPrefixes.stream().anyMatch(prefix -> matches(path, prefix));
    }

    private boolean matches(String path, String prefix) {
        if (prefix.endsWith("/")) {
            return path.startsWith(prefix);
        }
        return path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + "?");
    }
}
