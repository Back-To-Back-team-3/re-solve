package com.backtoback.ai.hint.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

@Configuration
public class LlmCircuitBreakerConfig {

    @Bean
    public CircuitBreaker llmCircuitBreaker() {
        CircuitBreakerConfig config
            = CircuitBreakerConfig
                .custom()
                .failureRateThreshold(100f)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(Exception.class)
                .build();
        return CircuitBreaker.of("llm", config);
    }
}
