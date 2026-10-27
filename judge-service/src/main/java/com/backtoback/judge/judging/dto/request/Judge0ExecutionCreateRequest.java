package com.backtoback.judge.judging.dto.request;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Judge0 실행 생성 API에 전달할 본문을 담는다. */
public record Judge0ExecutionCreateRequest(
    @JsonProperty("language_id") int languageId,
    @JsonProperty("compiler_options") String compilerOptions,
    @JsonProperty("source_code") String sourceCode,
    String stdin,
    @JsonProperty("cpu_time_limit") BigDecimal cpuTimeLimit,
    @JsonProperty("wall_time_limit") BigDecimal wallTimeLimit,
    @JsonProperty("memory_limit") int memoryLimit,
    @JsonProperty("enable_network") boolean enableNetwork
) {
}
