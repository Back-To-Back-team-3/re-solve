package com.backtoback.problem.problem.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** 숨김 테스트와 회원별 풀이 상태를 제외한 고정 회차 본문. */
public record ProblemContentResponse(
    String problemId,
    String problemRevisionId,
    int revisionNumber,
    String title,
    int difficulty,
    List<ProblemTagResponse> tags,
    OffsetDateTime publishedAt,
    String description,
    String constraints,
    String examples,
    Map<String, Object> functionSpec,
    ExecutionLimitsResponse executionLimits,
    List<StarterCodeResponse> starterCodes,
    List<SampleCaseResponse> samples,
    List<String> supportedLanguages
) {
    /**
     * 고정 자료를 숨김 테스트 없는 본문 응답으로 변환한다.
     *
     * @param value 조회한 원문
     * @return 내부 고정 회차 본문
     */
    public static ProblemContentResponse from(
        com.backtoback.problem.problem.service.ProblemContentService.Content value
    ) {
        var fixed = value.fixed();
        var revision = fixed.revision();
        return new ProblemContentResponse(
            fixed.problem().getId().toString(),
            revision.getId().toString(),
            revision.getRevisionNumber(),
            revision.getTitle(),
            revision.getDifficulty(),
            fixed.tags().stream().map(tag -> new ProblemTagResponse(Long.toString(tag.id()), tag.name())).toList(),
            revision.getCreatedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            value.body().description(),
            value.body().constraints(),
            value.body().examples(),
            fixed.function(),
            ExecutionLimitsResponse.from(revision),
            value.starterCodes(),
            value
                .samples()
                .stream()
                .map(
                    sample -> new SampleCaseResponse(
                        Long.toString(sample.id()),
                        sample.content().argumentTexts(),
                        sample.content().expectedReturnText()
                    )
                )
                .toList(),
            fixed.profiles().stream().map(profile -> profile.language()).toList()
        );
    }
}
