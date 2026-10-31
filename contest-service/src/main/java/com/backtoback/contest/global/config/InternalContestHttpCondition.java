package com.backtoback.contest.global.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 내부 HTTP 구현을 명시적으로 선택했을 때 Controller를 등록한다.
 * Stub은 local·internal-stub 프로필과 stub 구현을 함께 선택했을 때만 등록한다.
 */
public class InternalContestHttpCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        String mode = environment.getProperty("contest.internal-http.mode", "disabled");
        return "real".equals(mode)
            || ("stub".equals(mode) && environment.acceptsProfiles(Profiles.of("local & internal-stub")));
    }
}
