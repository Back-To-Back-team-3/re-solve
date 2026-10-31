package com.backtoback.problem.problem.dto.response;

import java.util.Map;

public record ValidationCaseResponse(
    String testAssetId,
    boolean isSample,
    Map<String, String> argumentTexts,
    String expectedReturnText
) {
}
