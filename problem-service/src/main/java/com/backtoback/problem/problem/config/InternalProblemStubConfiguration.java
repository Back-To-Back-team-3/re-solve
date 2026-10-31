package com.backtoback.problem.problem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.backtoback.problem.problem.service.InternalProblemService;
import com.backtoback.problem.problem.service.InternalProblemStubService;

/**
 * 명시적으로 활성화한 internal-stub 프로필에만 내부 API Stub을 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(InternalProblemStubCondition.class)
public class InternalProblemStubConfiguration {
    @Bean
    InternalProblemService internalProblemService() {
        return new InternalProblemStubService();
    }
}
