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

    /**
     * 자료의 회차 연결과 실행·이벤트 필수값을 검증한다.
     *
     * @param requestedProblem 요청한 문제 식별자
     * @param requestedRevision 요청한 고정 회차 식별자
     * @throws IllegalArgumentException 회차가 다르거나 필수값·범위·테스트 구성이 올바르지 않은 경우
     */
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

    /** 접수 시 고정한 테스트 식별자·순서·공개 여부와 원문 참조. */
    public record TestCase(String testAssetId, int testOrder, Boolean isSample, Asset asset) {
    }

    /** 객체 버전을 고정해 같은 키의 새 업로드를 읽지 않는다. */
    public record Asset(String objectKey, String objectVersion, String sha256) {
        /**
         * 고정 객체 버전과 SHA-256 체크섬의 필수 형식을 검증한다.
         *
         * @throws IllegalArgumentException 객체 키·버전 또는 체크섬이 올바르지 않은 경우
         */
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
