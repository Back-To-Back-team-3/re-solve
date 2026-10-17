package com.backtoback.gateway.filter;

import org.springframework.core.Ordered;

/**
 * 게이트웨이 글로벌 필터 실행 순서 정의.
 */
public final class FilterOrder {

    public static final int REMOVE_EXTERNAL_HEADERS = Ordered.HIGHEST_PRECEDENCE;
    public static final int CORRELATION_ID = Ordered.HIGHEST_PRECEDENCE + 1;
    public static final int JWT_AUTHENTICATION = -100;
    public static final int ADMIN_AUTHORIZATION = -90;

    private FilterOrder() {}
}
