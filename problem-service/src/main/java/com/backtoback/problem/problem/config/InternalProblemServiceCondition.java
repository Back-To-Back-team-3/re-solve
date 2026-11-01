package com.backtoback.problem.problem.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 선택한 내부 API 구현이 활성 상태일 때만 관련 Bean을 등록한다. */
public class InternalProblemServiceCondition implements Condition {
    private static final String ENABLED_PROPERTY = "problem.internal-api.enabled";
    private static final String IMPLEMENTATION_PROPERTY = "problem.internal-api.implementation";
    private static final String STUB_ENABLED_PROPERTY = "problem.internal-stub.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) {
            return false;
        }
        if (environment.acceptsProfiles(Profiles.of("internal-stub"))) {
            return environment.getProperty(STUB_ENABLED_PROPERTY, Boolean.class, false)
                && "stub".equals(environment.getProperty(IMPLEMENTATION_PROPERTY));
        }
        return "real".equals(environment.getProperty(IMPLEMENTATION_PROPERTY));
    }
}
