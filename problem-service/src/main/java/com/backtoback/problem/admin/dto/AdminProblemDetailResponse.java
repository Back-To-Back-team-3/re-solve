package com.backtoback.problem.admin.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.backtoback.problem.admin.service.AdminProblemQueryService.Content;
import com.backtoback.problem.admin.service.AdminProblemQueryService.Language;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.response.ExecutionLimitsResponse;
import com.backtoback.problem.problem.dto.response.JudgeValidationProgressResponse.TestResult;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 작성자의 회차 원문과 검증 상태(API 명세서 §2.3.3·§2.3.5). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminProblemDetailResponse(
    String problemId,
    String authorId,
    String scope,
    String visibility,
    int rowVersion,
    String problemRevisionId,
    int revisionNumber,
    OffsetDateTime publishedAt,
    String title,
    String description,
    String constraints,
    String examples,
    int difficulty,
    List<String> tagIds,
    FunctionSpec functionSpec,
    ExecutionLimitsResponse executionLimits,
    List<TestCase> testCases,
    String currentProblemRevisionId,
    List<String> supportedLanguages,
    List<ReferenceSolution> referenceSolutions,
    List<LanguageResult> languageResults
) {
    /**
     * 고정 자료를 관리자 상세 응답으로 변환한다.
     *
     * @param value 원문과 진행 결과
     * @return 상세 응답
     */
    public static AdminProblemDetailResponse from(Content value) {
        var snapshot = value.snapshot();
        var summary = snapshot.summary();
        var problem = summary.problem();
        var revision = summary.revision();
        return new AdminProblemDetailResponse(
            problem.getId().toString(),
            problem.getAuthorId().toString(),
            problem.getScope().name(),
            problem.getVisibility().name(),
            problem.getRowVersion(),
            revision.getId().toString(),
            revision.getRevisionNumber(),
            revision.getCreatedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            revision.getTitle(),
            value.body().description(),
            value.body().constraints(),
            value.body().examples(),
            revision.getDifficulty(),
            snapshot.tags(),
            value.function(),
            ExecutionLimitsResponse.from(revision),
            value
                .tests()
                .stream()
                .map(
                    test -> new TestCase(
                        test.content().argumentTexts(),
                        test.content().expectedReturnText(),
                        test.sample(),
                        Long.toString(test.id())
                    )
                )
                .toList(),
            summary.current() == null ? null : summary.current().toString(),
            summary.languages(),
            value
                .languages()
                .stream()
                .map(language -> new ReferenceSolution(language.local().language(), language.code()))
                .toList(),
            value.languages().stream().map(LanguageResult::from).toList()
        );
    }

    /** 전체 테스트 원문과 조회 전용 파일 식별자. */
    public record TestCase(
        Map<String, String> argumentTexts,
        String expectedReturnText,
        boolean isSample,
        String testAssetId
    ) {
    }
    /** 통과 코드 또는 최신 요청 코드. */
    public record ReferenceSolution(String language, String sourceCode) {
    }
    /** 언어별 최신 요청의 Judge 진행 상태. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LanguageResult(
        String validationId,
        String language,
        String status,
        List<TestResult> tests,
        Integer maxTimeMs,
        String message,
        boolean resultApplied
    ) {
        /**
         * 작업 미생성을 REQUESTED로 표시한다.
         *
         * @param value 고정 요청과 Judge 진행
         * @return 언어별 진행
         */
        public static LanguageResult from(Language value) {
            var progress = value.progress();
            return new LanguageResult(
                Long.toString(value.local().validationId()),
                value.local().language(),
                progress == null ? "REQUESTED" : progress.status(),
                progress == null ? List.of() : progress.tests(),
                progress == null ? null : progress.maxTimeMs(),
                progress == null ? null : progress.message(),
                value.local().applied()
            );
        }
    }
}
