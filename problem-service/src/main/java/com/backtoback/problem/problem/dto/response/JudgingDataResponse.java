package com.backtoback.problem.problem.dto.response;

import java.util.List;
import java.util.Map;

import com.backtoback.problem.problem.service.ProblemJudgingStore.Snapshot;

public record JudgingDataResponse(
    String problemId,
    String problemRevisionId,
    String problemTitle,
    int problemDifficulty,
    List<String> problemTags,
    List<String> problemTagIds,
    Map<String, Object> functionSpec,
    ExecutionLimitsResponse executionLimits,
    List<String> supportedLanguages,
    List<ExecutionProfileResponse> executionProfiles,
    List<TestCaseReferenceResponse> cases
) {
    /**
     * 고정 회차 메타데이터와 원문 없는 파일 참조를 변환한다.
     *
     * @param value 고정 자료
     * @return Judge 채점 자료
     */
    public static JudgingDataResponse from(Snapshot value) {
        var revision = value.revision();
        return new JudgingDataResponse(
            value.problem().getId().toString(),
            revision.getId().toString(),
            revision.getTitle(),
            revision.getDifficulty(),
            value.tags().stream().map(tag -> tag.name()).toList(),
            value.tags().stream().map(tag -> Long.toString(tag.id())).toList(),
            value.function(),
            ExecutionLimitsResponse.from(revision),
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
                .toList(),
            value
                .tests()
                .stream()
                .map(
                    test -> new TestCaseReferenceResponse(
                        Long.toString(test.id()),
                        test.order(),
                        test.sample(),
                        new AssetReferenceResponse(test.key(), test.version(), test.sha256())
                    )
                )
                .toList()
        );
    }
}
