package com.backtoback.problem.problem.dto.response;

import java.util.Map;

public record SampleCaseResponse(String testAssetId, Map<String, String> argumentTexts, String expectedReturnText) {
}
