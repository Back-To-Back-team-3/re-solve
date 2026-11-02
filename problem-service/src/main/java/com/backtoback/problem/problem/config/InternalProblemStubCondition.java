package com.backtoback.problem.problem.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Stub 전용 프로필과 구현 선택값이 맞을 때만 Stub Bean을 등록한다. */
public class InternalProblemStubCondition implements Condition {
    private static final String API_ENABLED_PROPERTY = "problem.internal-api.enabled";
    private static final String IMPLEMENTATION_PROPERTY = "problem.internal-api.implementation";
    private static final String STUB_ENABLED_PROPERTY = "problem.internal-stub.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        return environment.acceptsProfiles(Profiles.of("internal-stub"))
            && environment.getProperty(API_ENABLED_PROPERTY, Boolean.class, false)
            && "stub".equals(environment.getProperty(IMPLEMENTATION_PROPERTY))
            && environment.getProperty(STUB_ENABLED_PROPERTY, Boolean.class, false);
    }
}
