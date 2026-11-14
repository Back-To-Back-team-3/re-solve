package com.backtoback.integration.github.domain;

/**
 * GitHub 연동 해제 사유 (erd.md > 4.8.1).
 */
public enum GithubDisconnectedReason {
    USER_REQUEST, AUTH_EXPIRED, WITHDRAWN
}
