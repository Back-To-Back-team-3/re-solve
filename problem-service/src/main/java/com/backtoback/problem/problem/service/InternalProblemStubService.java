package com.backtoback.problem.problem.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.request.ContestProblemLinkRequest;
import com.backtoback.problem.problem.dto.response.AssetReferenceResponse;
import com.backtoback.problem.problem.dto.response.ContestProblemLinkResponse;
import com.backtoback.problem.problem.dto.response.ExecutionLimitsResponse;
import com.backtoback.problem.problem.dto.response.ExecutionProfileResponse;
import com.backtoback.problem.problem.dto.response.JudgingDataResponse;
import com.backtoback.problem.problem.dto.response.JudgingValidationResponse;
import com.backtoback.problem.problem.dto.response.ProblemContentResponse;
import com.backtoback.problem.problem.dto.response.ProblemOperationResponse;
import com.backtoback.problem.problem.dto.response.ProblemSummaryListResponse;
import com.backtoback.problem.problem.dto.response.ProblemTagResponse;
import com.backtoback.problem.problem.dto.response.SampleCaseResponse;
import com.backtoback.problem.problem.dto.response.StarterCodeResponse;
import com.backtoback.problem.problem.dto.response.TestCaseReferenceResponse;
import com.backtoback.problem.problem.dto.response.ValidationCaseResponse;
import com.backtoback.problem.problem.dto.response.ValidationDataResponse;

/**
 * 정해진 개발 데이터를 응답하며 저장소 조회나 실제 문제 상태 변경은 수행하지 않는다.
 */
public class InternalProblemStubService implements InternalProblemService {
    private static final Pattern DECIMAL_ID = Pattern.compile("[1-9][0-9]*");
    private static final Pattern ACCESS_CONTEXT = Pattern.compile("(EXAM|CONTEST|DIAGNOSIS):[1-9][0-9]*");
    private static final String RUNTIME_KEY = "fbc2a5e3e4cc2495a37b2e73b4824478ee5103e910cbd21ec1e109defd598c9c";
    private static final String RUNNER_VERSION = "java-runner-v1";
    private static final ExecutionLimitsResponse EXECUTION_LIMITS = new ExecutionLimitsResponse(2000, 262144, 7000, 64);
    private static final Map<String, Object> FUNCTION_SPEC = functionSpec();
    private static final List<ExecutionProfileResponse> EXECUTION_PROFILES
        = List.of(new ExecutionProfileResponse("java", RUNTIME_KEY, RUNNER_VERSION));
    private static final List<String> SUPPORTED_LANGUAGES = List.of("java");
    private static final List<String> TAG_IDS = List.of("1");
    private static final String STARTER_CODE
        = "class Solution {\n    public int solution(int a, int b) {\n        return 0;\n    }\n}\n";
    private static final String VALIDATION_CODE
        = "class Solution {\n    public int solution(int a, int b) {\n        return a + b;\n    }\n}\n";
    private static final String REQUEST_HASH = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    @Override
    public JudgingValidationResponse getJudgingValidation(String problemId, String revisionId) {
        requireKnownProblem(problemId);
        requireRevision(problemId, revisionId);
        return new JudgingValidationResponse(
            problemId,
            revisionId,
            "601".equals(problemId) ? "EXAM_ONLY" : "GENERAL",
            "601".equals(problemId) ? "PRIVATE" : "PUBLIC",
            1,
            TAG_IDS,
            2,
            testAssetIds(),
            SUPPORTED_LANGUAGES,
            EXECUTION_PROFILES
        );
    }

    @Override
    public JudgingDataResponse getJudgingData(String problemId, String revisionId) {
        requireKnownProblem(problemId);
        requireRevision(problemId, revisionId);
        return new JudgingDataResponse(
            problemId,
            revisionId,
            FUNCTION_SPEC,
            EXECUTION_LIMITS,
            SUPPORTED_LANGUAGES,
            EXECUTION_PROFILES,
            List
                .of(
                    new TestCaseReferenceResponse(
                        testAssetIds().get(0),
                        1,
                        true,
                        assetReference(testAssetIds().get(0))
                    ),
                    new TestCaseReferenceResponse(
                        testAssetIds().get(1),
                        2,
                        false,
                        assetReference(testAssetIds().get(1))
                    )
                )
        );
    }

    @Override
    public ProblemContentResponse getContent(String problemId, String revisionId, String accessContext) {
        requireAccessContext(accessContext);
        requireKnownProblem(problemId);
        requireRevision(problemId, revisionId);
        boolean contestOnly = "601".equals(problemId);
        return new ProblemContentResponse(
            problemId,
            revisionId,
            1,
            contestOnly ? "대회 전용 두 수의 합" : "두 수의 합",
            1,
            List.of(new ProblemTagResponse("1", "구현")),
            "2026-09-27T10:00:00+09:00",
            contestOnly ? "## 문제\n두 정수 a와 b의 합을 반환하세요." : "## 문제\n두 정수 a와 b의 합을 반환하세요.\n\n![덧셈 예시](image:701)",
            "- 입력 범위: -1000 <= a, b <= 1000",
            "| a | b | 반환값 |\n|---|---|---|\n| 1 | 2 | 3 |",
            FUNCTION_SPEC,
            EXECUTION_LIMITS,
            List.of(new StarterCodeResponse("java", STARTER_CODE)),
            List.of(new SampleCaseResponse(testAssetIds().get(0), Map.of("a", "1", "b", "2"), "3")),
            SUPPORTED_LANGUAGES
        );
    }

    @Override
    public byte[] getImage(String problemId, String revisionId, String imageId, String accessContext) {
        requireAccessContext(accessContext);
        requireKnownProblem(problemId);
        requireRevision(problemId, revisionId);
        requireDecimalId(imageId);
        if (!"301".equals(problemId) || !"1001".equals(revisionId) || !"701".equals(imageId)) {
            throw problemNotFound(ProblemErrorCode.PROBLEM_NOT_FOUND);
        }
        try {
            try (var image = new ClassPathResource("internal-stub/images/701.svg").getInputStream()) {
                return image.readAllBytes();
            }
        } catch (IOException exception) {
            throw problemNotFound(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public ProblemSummaryListResponse getSummaries(List<String> ids, String purpose) {
        if (ids == null || ids.isEmpty() || ids.size() > 50) {
            throw problemNotFound(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        if (!List.of("ASSIGNMENT", "EXAM", "CONTEST").contains(purpose)) {
            throw problemNotFound(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String problemId : ids) {
            requireDecimalId(problemId);
            items.add(summaryItem(problemId, purpose));
        }
        return new ProblemSummaryListResponse(items);
    }

    @Override
    public ValidationDataResponse getValidationData(String validationId) {
        requireDecimalId(validationId);
        if (!"2001".equals(validationId)) {
            throw problemNotFound(ProblemErrorCode.PROBLEM_VALIDATION_NOT_FOUND);
        }
        return new ValidationDataResponse(
            "2001",
            "1001",
            "java",
            REQUEST_HASH,
            VALIDATION_CODE,
            FUNCTION_SPEC,
            EXECUTION_LIMITS,
            List
                .of(
                    new ValidationCaseResponse("501", true, Map.of("a", "1", "b", "2"), "3"),
                    new ValidationCaseResponse("502", false, Map.of("a", "-5", "b", "7"), "2")
                )
        );
    }

    @Override
    public ContestProblemLinkResponse linkContestProblem(String problemId, ContestProblemLinkRequest request) {
        requireKnownProblem(problemId);
        requireDecimalId(request.contestId());
        requireDecimalId(request.problemRevisionId());
        requireDecimalId(request.actorMemberId());
        if (!"601".equals(problemId)) {
            throw problemNotFound(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
        if (request.rowVersion() != 7) {
            throw problemNotFound(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
        }
        if (!"8".equals(request.contestId()) || !"2001".equals(request.problemRevisionId())
            || !"7".equals(request.actorMemberId())) {
            throw problemNotFound(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
        return new ContestProblemLinkResponse("601", "8", "2001", 8, "7");
    }

    @Override
    public ProblemOperationResponse getOperation(String problemId, String operationId) {
        requireDecimalId(problemId);
        if ("301".equals(problemId) && "problem-hide-301".equals(operationId)) {
            return new ProblemOperationResponse("problem-hide-301", "COMMITTED", "10", "PRIVATE");
        }
        if ("302".equals(problemId) && "problem-hide-302".equals(operationId)) {
            return new ProblemOperationResponse("problem-hide-302", "PENDING", "10", "PUBLIC");
        }
        if ("301".equals(problemId) && "problem-restore-301".equals(operationId)) {
            return new ProblemOperationResponse("problem-restore-301", "ABORTED", "9", "PUBLIC");
        }
        throw problemNotFound(ProblemErrorCode.PROBLEM_NOT_FOUND);
    }

    private Map<String, Object> summaryItem(String problemId, String purpose) {
        if ("999".equals(problemId)) {
            return Map.of("problemId", problemId, "isAvailable", false);
        }
        if (!List.of("301", "302", "303", "601").contains(problemId)) {
            return Map.of("problemId", problemId, "isAvailable", false);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("problemId", problemId);
        if ("303".equals(problemId)) {
            item.put("scope", "GENERAL");
            item.put("visibility", "PUBLIC");
            item.put("isAvailable", false);
            item.put("sourceVersion", 1);
            item.put("problemRevisionId", null);
            item.put("revisionNumber", null);
            item.put("supportedLanguages", List.of());
            return item;
        }
        if ("302".equals(problemId)) {
            item.put("scope", "GENERAL");
            item.put("visibility", "PRIVATE");
            item.put("isAvailable", false);
            item.put("sourceVersion", 1);
            item.put("problemRevisionId", null);
            item.put("revisionNumber", null);
            item.put("supportedLanguages", List.of());
            return item;
        }
        boolean contestOnly = "601".equals(problemId);
        item.put("scope", contestOnly ? "EXAM_ONLY" : "GENERAL");
        item.put("visibility", contestOnly ? "PRIVATE" : "PUBLIC");
        item.put("isAvailable", !contestOnly || "CONTEST".equals(purpose));
        item.put("sourceVersion", 7);
        item.put("problemRevisionId", contestOnly ? "2001" : "1001");
        item.put("revisionNumber", 1);
        item.put("title", contestOnly ? "대회 전용 두 수의 합" : "두 수의 합");
        item.put("difficulty", 1);
        item.put("tags", List.of(Map.of("tagId", "1", "name", "구현")));
        item.put("supportedLanguages", SUPPORTED_LANGUAGES);
        return item;
    }

    private void requireKnownProblem(String problemId) {
        requireDecimalId(problemId);
        if (!List.of("301", "302", "601").contains(problemId)) {
            throw problemNotFound(ProblemErrorCode.PROBLEM_NOT_FOUND);
        }
    }

    private void requireRevision(String problemId, String revisionId) {
        requireDecimalId(revisionId);
        if (("301".equals(problemId) && "1001".equals(revisionId))
            || ("601".equals(problemId) && "2001".equals(revisionId))) {
            return;
        }
        throw problemNotFound(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND);
    }

    private List<String> testAssetIds() {
        return List.of("501", "502");
    }

    private AssetReferenceResponse assetReference(String testAssetId) {
        String checksum
            = "501".equals(testAssetId) ? "20bd49ca3d007a819667e1406de27e22db53a964c961ba4ec06af2c9654ee68c"
                : "c79b5dcc033d67c0d1a9bc763198db13eda38b5f6deb570141ec7c48e28be6ed";
        return new AssetReferenceResponse("test-cases/" + testAssetId + ".json", null, checksum);
    }

    private void requireAccessContext(String accessContext) {
        if (!ACCESS_CONTEXT.matcher(accessContext).matches()) {
            throw problemNotFound(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    private void requireDecimalId(String value) {
        if (value == null || !DECIMAL_ID.matcher(value).matches()) {
            throw problemNotFound(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        try {
            Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw problemNotFound(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    private ProblemException problemNotFound(com.backtoback.common.exception.ErrorCode errorCode) {
        return new ProblemException(errorCode);
    }

    private static Map<String, Object> functionSpec() {
        return Map
            .of(
                "name",
                "solution",
                "parameters",
                List
                    .of(
                        Map.of("name", "a", "type", Map.of("kind", "INT32")),
                        Map.of("name", "b", "type", Map.of("kind", "INT32"))
                    ),
                "returnType",
                Map.of("kind", "INT32")
            );
    }
}
