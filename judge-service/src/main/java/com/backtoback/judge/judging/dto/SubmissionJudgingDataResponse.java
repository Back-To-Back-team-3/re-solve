package com.backtoback.judge.judging.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.util.Assert;

/** 고정 회차의 실행 자료와 결과 이벤트 메타데이터. (API 명세서 §2.6.2) */
public record SubmissionJudgingDataResponse(
    String problemId,
    String problemRevisionId,
    String problemTitle,
    Integer problemDifficulty,
    List<String> problemTags,
    List<String> problemTagIds,
    FunctionSpec functionSpec,
    ProblemValidationDataResponse.Limits executionLimits,
    List<TestCase> cases
) {

    /** 자료를 최신 회차로 바꾸거나 불완전한 이벤트를 발행하지 않도록 연결을 검증한다. */
    public void validate(long requestedProblem, long requestedRevision) {
        Assert
            .isTrue(
                Long.toString(requestedProblem).equals(problemId)
                    && Long.toString(requestedRevision).equals(problemRevisionId),
                "고정 회차가 일치하지 않습니다."
            );
        Assert.hasText(problemTitle, "문제 제목이 필요합니다.");
        Assert.isTrue(problemDifficulty != null && problemDifficulty >= 1 && problemDifficulty <= 3, "난이도가 올바르지 않습니다.");
        Assert.notEmpty(problemTagIds, "태그가 필요합니다.");
        Assert.isTrue(problemTags != null && problemTags.size() == problemTagIds.size(), "태그 대응이 필요합니다.");
        long previous = 0;
        for (int index = 0; index < problemTagIds.size(); index++) {
            long id = Long.parseLong(problemTagIds.get(index));
            Assert.isTrue(id > previous, "태그 ID는 중복 없이 오름차순이어야 합니다.");
            Assert.hasText(problemTags.get(index), "태그 이름이 필요합니다.");
            previous = id;
        }
        Assert.notNull(functionSpec, "함수 명세가 필요합니다.");
        Assert.notNull(executionLimits, "실행 제한이 필요합니다.");
        executionLimits.validate();
        Assert.notEmpty(cases, "테스트가 필요합니다.");
        var ids = new HashSet<String>();
        var orders = new HashSet<Integer>();
        for (var test : cases) {
            Assert
                .isTrue(
                    test != null && test.testOrder() > 0 && test.isSample() != null
                        && Long.parseLong(test.testAssetId()) > 0 && ids.add(test.testAssetId())
                        && orders.add(test.testOrder()),
                    "테스트 메타데이터가 올바르지 않습니다."
                );
            Assert.notNull(test.asset(), "파일 참조가 필요합니다.");
            test.asset().validate();
        }
    }

    public record TestCase(String testAssetId, int testOrder, Boolean isSample, Asset asset) {
    }

    /** 객체 버전을 고정해 같은 키의 새 업로드를 읽지 않는다. */
    public record Asset(String objectKey, String objectVersion, String sha256) {
        public void validate() {
            Assert.hasText(objectKey, "객체 키가 필요합니다.");
            Assert.hasText(objectVersion, "객체 버전이 필요합니다.");
            Assert.isTrue(sha256 != null && sha256.matches("[0-9a-fA-F]{64}"), "체크섬이 필요합니다.");
        }
    }

    /** 문자열 JSON을 그대로 유지하여 큰 정수의 정밀도 손실을 막는다. */
    public record TestContent(Map<String, String> argumentTexts, String expectedReturnText) {
    }
}
