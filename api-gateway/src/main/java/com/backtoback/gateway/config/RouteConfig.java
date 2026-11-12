package com.backtoback.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cloud Gateway 라우팅 설정.
 * application.yaml의 YAML 바인딩 대신 프로그래매틱 방식으로 정의한다.
 */
@Configuration
public class RouteConfig {

    @Value("${NOTIFICATION_SERVICE_URL:http://notification-service:8080}")
    private String notificationServiceUrl;

    @Value("${MEMBER_SERVICE_URL:http://member-service:8080}")
    private String memberServiceUrl;

    @Value("${PROBLEM_SERVICE_URL:http://problem-service:8080}")
    private String problemServiceUrl;

    @Value("${JUDGE_SERVICE_URL:http://judge-service:8080}")
    private String judgeServiceUrl;

    @Value("${CONTEST_SERVICE_URL:http://contest-service:8080}")
    private String contestServiceUrl;

    @Value("${STUDY_SERVICE_URL:http://study-service:8080}")
    private String studyServiceUrl;

    @Value("${INTEGRATION_SERVICE_URL:http://integration-service:8080}")
    private String integrationServiceUrl;

    @Value("${AI_SERVICE_URL:http://ai-service:8080}")
    private String aiServiceUrl;

    @Value("${ANALYTICS_SERVICE_URL:http://analytics-service:8080}")
    private String analyticsServiceUrl;

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder
            .routes()
            .route(
                "notification-sse-service",
                r -> r
                    .path("/api/v1/notifications/subscribe")
                    .metadata("response-timeout", -1)
                    .uri(notificationServiceUrl)
            )
            .route(
                "notification-service",
                r -> r.path("/api/v1/notifications/**", "/api/v1/admin/notifications/**").uri(notificationServiceUrl)
            )
            .route(
                "member-service",
                r -> r
                    .path("/api/v1/auth/**", "/api/v1/members/**", "/api/v1/diagnoses/**", "/api/v1/admin/members/**")
                    .uri(memberServiceUrl)
            )
            .route(
                "problem-service",
                r -> r
                    .path(
                        "/api/v1/problems",
                        "/api/v1/problems/**",
                        "/api/v1/solution-shares",
                        "/api/v1/solution-shares/**",
                        "/api/v1/admin/problems/**"
                    )
                    .uri(problemServiceUrl)
            )
            .route(
                "judge-service",
                r -> r
                    .path(
                        "/api/v1/judge/**",
                        "/api/v1/submissions",
                        "/api/v1/submissions/**",
                        "/api/v1/runs",
                        "/api/v1/runs/**",
                        "/api/v1/me/submissions",
                        "/api/v1/me/submissions/**",
                        "/api/v1/admin/judge-jobs/**",
                        "/api/v1/admin/submissions/**"
                    )
                    .uri(judgeServiceUrl)
            )
            .route(
                "contest-service",
                r -> r
                    .path(
                        "/api/v1/studies/*/exams",
                        "/api/v1/studies/*/exams/**",
                        "/api/v1/exams/**",
                        "/api/v1/contests/**",
                        "/api/v1/admin/exams/**",
                        "/api/v1/admin/contests/**",
                        "/api/v1/admin/exam-submissions/**",
                        "/api/v1/admin/contest-submissions/**",
                        "/api/v1/admin/contest-operations/**"
                    )
                    .uri(contestServiceUrl)
            )
            .route("study-service", r -> r.path("/api/v1/studies/**", "/api/v1/admin/studies/**").uri(studyServiceUrl))
            .route("integration-service", r -> r.path("/api/v1/integrations/**").uri(integrationServiceUrl))
            .route("ai-service", r -> r.path("/api/v1/hint-requests/**").uri(aiServiceUrl))
            .route("analytics-service", r -> r.path("/api/v1/analytics/**").uri(analyticsServiceUrl))
            .build();
    }
}
