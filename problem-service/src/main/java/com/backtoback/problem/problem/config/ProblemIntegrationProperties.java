package com.backtoback.problem.problem.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/** Judge 조회 주소와 소비자 전환 후 사용하는 문제 상태 SNS 목적지. */
@Validated
@ConfigurationProperties("problem.integration")
public record ProblemIntegrationProperties(
    @NotNull @DefaultValue("http://judge-service:8080") URI judgeUrl,
    String topicArn
) {
    /**
     * Judge 주소가 호스트를 포함한 절대 HTTP(S) URI인지 확인한다.
     *
     * @return HTTP(S) 주소와 호스트가 모두 있으면 true
     */
    @AssertTrue(message = "judgeUrl must be an absolute HTTP(S) URI with a host")
    public boolean isJudgeUrlValid() {
        return judgeUrl != null && judgeUrl.getHost() != null
            && ("http".equalsIgnoreCase(judgeUrl.getScheme()) || "https".equalsIgnoreCase(judgeUrl.getScheme()));
    }
}
