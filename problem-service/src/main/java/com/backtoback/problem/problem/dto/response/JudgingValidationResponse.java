package com.backtoback.problem.problem.dto.response;

import java.util.List;

import com.backtoback.problem.problem.service.ProblemJudgingStore.Snapshot;

/** Judge가 제출 조건을 검사하는 데 사용하는 문제 상태와 고정 회차의 통과 환경. */
public record JudgingValidationResponse(
    String problemId,
    String problemRevisionId,
    String scope,
    String visibility,
    int difficulty,
    List<String> tagIds,
    int totalCount,
    List<String> testAssetIds,
    List<String> supportedLanguages,
    List<ExecutionProfileResponse> executionProfiles
) {
    /**
     * 고정 회차의 상태와 통과 환경을 사전 검증 응답으로 변환한다.
     *
     * @param value 고정 자료
     * @return 사전 검증 응답
     */
    public static JudgingValidationResponse from(Snapshot value) {
        return new JudgingValidationResponse(
            value.problem().getId().toString(),
            value.revision().getId().toString(),
            value.problem().getScope().name(),
            value.problem().getVisibility().name(),
            value.revision().getDifficulty(),
            value.tags().stream().map(tag -> Long.toString(tag.id())).toList(),
            value.tests().size(),
            value.tests().stream().map(test -> Long.toString(test.id())).toList(),
            value.profiles().stream().map(profile -> profile.language()).toList(),
            value
                .profiles()
                .stream()
                .map(
                    profile -> new ExecutionProfileResponse(
                        profile.language(),
                        profile.runtimeKey(),
                        profile.runnerVersion()
                    )
                )
                .toList()
        );
    }
}
