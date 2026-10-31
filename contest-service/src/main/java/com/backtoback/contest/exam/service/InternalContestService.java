package com.backtoback.contest.exam.service;

import com.backtoback.contest.exam.dto.ActiveParticipationResponse;
import com.backtoback.contest.exam.dto.ContextValidateRequest;
import com.backtoback.contest.exam.dto.ContextValidationResponse;
import com.backtoback.contest.exam.dto.ExamRejudgeEligibilityResponse;
import com.backtoback.contest.exam.dto.FinalizedResultResponse;
import com.backtoback.contest.exam.dto.GuardCompletionResponse;
import com.backtoback.contest.exam.dto.GuardFinishRequest;
import com.backtoback.contest.exam.dto.GuardPreparationResponse;
import com.backtoback.contest.exam.dto.GuardPrepareRequest;
import com.backtoback.contest.exam.dto.HintBlockResponse;
import com.backtoback.contest.exam.dto.ProblemUsageResponse;
import com.backtoback.contest.exam.dto.ShareableSubmissionResponse;
import com.backtoback.contest.exam.dto.StudyExamResponse;

/**
 * 다른 서비스에 시험·대회 내부 조회와 자원 작업 계약을 제공한다. (API 명세서 §4.7)
 * Controller·DTO를 유지한 채 개발 Stub과 실제 구현을 교체한다.
 */
public interface InternalContestService {
    /**
     * 문맥·고정 회차·입장·제출 소유권을 확인한다. 실패하면 {@code CONTEXT_NOT_FOUND},
     * {@code PROBLEM_REVISION_NOT_INCLUDED}, {@code CONTEXT_ACCESS_DENIED}를 반환한다.
     * Run 마감은 {@code EXAM_CLOSED}·{@code CONTEST_CLOSED}로 구분한다. (API 명세서 §4.7.1)
     */
    ContextValidationResponse validateContext(ContextValidateRequest request);

    ActiveParticipationResponse getActiveParticipations(String memberId);

    /**
     * 자원 작업의 준비 결과를 제공한다. 회원·문제 거절은 {@code WITHDRAWAL_BLOCKED_ACTIVE_EXAM}·
     * {@code PROBLEM_IN_ACTIVE_EXAM}, 본문 상충은 {@code IDEMPOTENCY_KEY_CONFLICT},
     * 타 작업 점유·오래된 버전은 {@code CONTEST_OPERATION_BLOCKED}·{@code SOURCE_VERSION_CONFLICT}다.
     * 진행 중 스터디 시험만 ready=false로 대기한다. (API 명세서 §4.7.3)
     */
    GuardPreparationResponse prepareGuard(String resourceType, String resourceId, GuardPrepareRequest request);

    /**
     * 완료 결과를 제공한다. 작업 ID·버전 불일치는 {@code OPERATION_NOT_CURRENT}·
     * {@code SOURCE_VERSION_CONFLICT}, 취소된 작업의 완료는 {@code OPERATION_ALREADY_COMPLETED}다.
     * 준비가 차단되거나 스터디 시험이 대기 중이면 {@code CONTEST_OPERATION_BLOCKED}다. (API 명세서 §4.7.4)
     */
    GuardCompletionResponse completeGuard(String resourceType, String resourceId, GuardFinishRequest request);

    /**
     * 취소 결과를 제공한다. 작업 ID·버전 불일치는 {@code OPERATION_NOT_CURRENT}·
     * {@code SOURCE_VERSION_CONFLICT}, 완료된 작업의 취소는 {@code OPERATION_ALREADY_COMPLETED}다.
     * 준비 전 BLOCKED는 취소해도 BLOCKED를 유지한다. (API 명세서 §4.7.4)
     */
    GuardCompletionResponse cancelGuard(String resourceType, String resourceId, GuardFinishRequest request);

    StudyExamResponse getStudyExams(String studyId, String after, int size);

    ProblemUsageResponse getProblemUsages(String problemId);

    HintBlockResponse getHintBlocks(String memberId, String after, int size);

    /**
     * 본인 AC·부모 FINALIZED 조건을 확인한다. 없는 접수는 {@code CONTEXT_NOT_FOUND},
     * 타인은 {@code CONTEXT_ACCESS_DENIED}, 공유 불가는 {@code SUBMISSION_NOT_SHAREABLE}다. (API 명세서 §4.7.8)
     */
    ShareableSubmissionResponse getShareableSubmission(String context, String receiptId, String memberId);

    /**
     * 확정 버전을 유지한 결과 페이지를 제공한다. 미확정은 {@code RESULT_NOT_FINALIZED},
     * 없는 버전은 {@code RESULT_REVISION_NOT_FOUND}, 없는 문맥은 {@code CONTEXT_NOT_FOUND}다. (API 명세서 §4.7.9)
     */
    FinalizedResultResponse getFinalizedResult(
        String context,
        String contextId,
        String resultRevision,
        String after,
        int size
    );

    /**
     * 부모 상태만 확인하며 재채점 접수나 확정을 보류하지 않는다.
     * 없는 시험은 {@code EXAM_NOT_FOUND}다. (API 명세서 §4.7.10)
     */
    ExamRejudgeEligibilityResponse getRejudgeEligibility(String examId);
}
