package com.backtoback.judge.submission.service.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.submission.domain.SubmissionDraft;

/**
 * 원격 검증이 끝난 제출·고정 테스트를 한 트랜잭션에 저장하는 내부 요청이다.
 *
 * @param submission 불변 제출 원본
 * @param deadlineAt 호출자가 결정한 전체 처리 기한
 * @param tests 고정 문제 회차의 전체 테스트 메타데이터
 * @param runtime 이미 확정한 실행 환경; 최초 선택 전에는 {@code null}
 */
public record SubmissionStorageRequest(
    SubmissionDraft submission,
    LocalDateTime deadlineAt,
    List<TestCase> tests,
    JudgeRuntime runtime
) {
    /**
     * 원문을 포함하지 않는 테스트 메타데이터다.
     *
     * @param testAssetId 외부 테스트 파일 ID
     * @param testOrder 회차 안 테스트 순번
     * @param sample 공개 테스트 여부
     */
    public record TestCase(long testAssetId, int testOrder, boolean sample) {
    }
}
