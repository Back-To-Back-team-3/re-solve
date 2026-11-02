package com.backtoback.problem.problem.dto.response;

public record TestCaseReferenceResponse(
    String testAssetId,
    int testOrder,
    boolean isSample,
    AssetReferenceResponse asset
) {
}
