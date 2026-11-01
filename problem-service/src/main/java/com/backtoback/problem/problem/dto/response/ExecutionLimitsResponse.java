package com.backtoback.problem.problem.dto.response;

public record ExecutionLimitsResponse(int timeMs, int memoryKb, int wallTimeMs, int outputLimitKb) {
}
