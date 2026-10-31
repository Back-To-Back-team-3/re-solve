package com.backtoback.contest.exam.service;

import static com.backtoback.contest.exam.service.InternalContestStubFixtures.CONTEXTS;
import static com.backtoback.contest.exam.service.InternalContestStubFixtures.GUARDS;
import static com.backtoback.contest.exam.service.InternalContestStubFixtures.NOW;
import static com.backtoback.contest.exam.service.InternalContestStubFixtures.SUBMISSIONS;
import static com.backtoback.contest.exam.service.InternalContestStubFixtures.participants;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
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
import com.backtoback.contest.exam.service.InternalContestStubFixtures.ContextFixture;
import com.backtoback.contest.exam.service.InternalContestStubFixtures.GuardFixture;
import com.backtoback.contest.exam.service.InternalContestStubFixtures.ParticipantFixture;
import com.backtoback.contest.exam.service.InternalContestStubFixtures.SubmissionFixture;
import com.backtoback.contest.global.config.InternalContestHttpCondition;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/**
 * 고정 개발 데이터로 시험·대회 내부 HTTP 응답을 재현한다. (API 명세서 §4.7)
 * DB·원격 서비스·업무 이벤트를 사용하지 않으며 실제 상태 전이·작업 저장·경합 방지를 수행하지 않는다.
 */
@Service
@Conditional(InternalContestHttpCondition.class)
@ConditionalOnProperty(
    name = "contest.internal-http.mode",
    havingValue = "stub"
)
public class InternalContestStubService implements InternalContestService {

    @Override
    public ContextValidationResponse validateContext(ContextValidateRequest request) {
        ContextFixture context = context(request.context(), request.contextId());
        var problem
            = context
                .problems()
                .stream()
                .filter(
                    item -> item.id().equals(request.problemId())
                        && item.revisionId().equals(request.problemRevisionId())
                )
                .findFirst()
                .orElseThrow(() -> new ContestException(ContestErrorCode.PROBLEM_REVISION_NOT_INCLUDED));
        var participant
            = participants(context)
                .stream()
                .filter(item -> item.memberId().equals(request.memberId()))
                .findFirst()
                .orElse(null);
        boolean publicRead
            = "CONTEST".equals(context.type()) && context.publicAfterEnd()
                && ("ENDED".equals(context.status()) || context.isFinalized())
                && ("READ_BODY".equals(request.action()) || "READ_IMAGE".equals(request.action()));
        if (!publicRead && (participant == null || participant.enteredAt() == null)) {
            throw new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED);
        }
        if ("SCHEDULED".equals(context.status()) || "CANCELED".equals(context.status())) {
            throw new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED);
        }
        if ("RUN".equals(request.action())) {
            if (!context.isRunning() || !"STARTED".equals(participant.status())) {
                throw new ContestException(
                    "EXAM".equals(context.type()) ? ContestErrorCode.EXAM_CLOSED : ContestErrorCode.CONTEST_CLOSED
                );
            }
        }
        if ("READ_SUBMISSION".equals(request.action())) {
            var submission = submission(request.context(), request.receiptId());
            if (!submission.contextId().equals(context.id()) || !submission.memberId().equals(request.memberId())
                || !submission.problemId().equals(request.problemId())
                || !submission.revisionId().equals(request.problemRevisionId())) {
                throw new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED);
            }
        }
        // 대회 종료 후 일반 열람은 실제 참가 여부와 관계없이 공개 열람 응답을 사용한다.
        return new ContextValidationResponse(
            true,
            publicRead ? null : participant.id(),
            problem.revisionId(),
            context.visibility(),
            publicRead ? null : context.endsAt(),
            NOW
        );
    }

    @Override
    public ActiveParticipationResponse getActiveParticipations(String memberId) {
        var items
            = CONTEXTS
                .stream()
                .filter(context -> !context.isFinalized() && !"CANCELED".equals(context.status()))
                .flatMap(
                    context -> participants(context)
                        .stream()
                        .filter(
                            item -> item.memberId().equals(memberId) && item.enteredAt() != null
                                && !"ABSENT".equals(item.status()) && !"CANCELED".equals(item.status())
                        )
                        .map(
                            item -> new ActiveParticipationResponse.Item(
                                context.type(),
                                context.id(),
                                item.id(),
                                item.enteredAt(),
                                item.status()
                            )
                        )
                )
                .toList();
        return new ActiveParticipationResponse(!items.isEmpty(), items);
    }

    /**
     * 고정 Guard 준비 상태를 반환한다. 실제 준비 상태를 저장하지 않으므로 트랜잭션을 사용하지 않는다.
     */
    @Override
    public GuardPreparationResponse prepareGuard(String resourceType, String resourceId, GuardPrepareRequest request) {
        String expectedType = switch (resourceType) {
            case "MEMBER" -> "WITHDRAWAL";
            case "STUDY" -> "STUDY_CLOSE";
            case "PROBLEM" -> "PROBLEM_HIDE";
            default -> throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        };
        GuardFixture guard = guard(resourceType, resourceId);
        if (guard.operationId().equals(request.operationId())
            && (!"10".equals(request.expectedSourceVersion()) || !expectedType.equals(request.operationType()))) {
            throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (!expectedType.equals(request.operationType())) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        if (new BigInteger(request.expectedSourceVersion()).compareTo(new BigInteger(guard.sourceVersion())) < 0
            && !guard.operationId().equals(request.operationId())) {
            throw new ContestException(ContestErrorCode.SOURCE_VERSION_CONFLICT);
        }
        if (!guard.operationId().equals(request.operationId())) {
            throw new ContestException(ContestErrorCode.CONTEST_OPERATION_BLOCKED);
        }
        if ("MEMBER".equals(resourceType) && getActiveParticipations(resourceId).withdrawalBlocked()) {
            throw new ContestException(ContestErrorCode.WITHDRAWAL_BLOCKED_ACTIVE_EXAM);
        }
        if ("PROBLEM".equals(resourceType) && getProblemUsages(resourceId).hasBlockingUsage()) {
            throw new ContestException(ContestErrorCode.PROBLEM_IN_ACTIVE_EXAM);
        }
        boolean waiting = "STUDY".equals(resourceType) && "12".equals(resourceId);
        return new GuardPreparationResponse(
            guard.operationId(),
            guard.state(),
            guard.result(),
            guard.isPending() && !waiting,
            NOW.minusMinutes(1),
            waiting ? List.of("51") : List.of(),
            waiting ? List.of("55") : List.of()
        );
    }

    /**
     * 고정 완료 응답을 계산하며 원천 상태나 Guard를 변경하지 않는다. (API 명세서 §4.7.4)
     */
    @Override
    public GuardCompletionResponse completeGuard(String resourceType, String resourceId, GuardFinishRequest request) {
        return finishGuard(resourceType, resourceId, request, true);
    }

    /**
     * 고정 취소 응답을 계산하며 준비 과정의 시험을 복원하지 않는다. (API 명세서 §4.7.4)
     */
    @Override
    public GuardCompletionResponse cancelGuard(String resourceType, String resourceId, GuardFinishRequest request) {
        return finishGuard(resourceType, resourceId, request, false);
    }

    private GuardCompletionResponse finishGuard(
        String resourceType,
        String resourceId,
        GuardFinishRequest request,
        boolean complete
    ) {
        GuardFixture guard = guard(resourceType, resourceId);
        if (!guard.operationId().equals(request.operationId())) {
            throw new ContestException(ContestErrorCode.OPERATION_NOT_CURRENT);
        }
        if ((complete && "ABORTED".equals(guard.result())) || (!complete && "COMMITTED".equals(guard.result()))) {
            throw new ContestException(ContestErrorCode.OPERATION_ALREADY_COMPLETED);
        }
        String expectedVersion = complete ? "11" : "10";
        if (!expectedVersion.equals(request.sourceVersion())) {
            throw new ContestException(ContestErrorCode.SOURCE_VERSION_CONFLICT);
        }
        if (complete && guard.isPending()
            && (("STUDY".equals(resourceType) && "12".equals(resourceId))
                || ("MEMBER".equals(resourceType) && getActiveParticipations(resourceId).withdrawalBlocked())
                || ("PROBLEM".equals(resourceType) && getProblemUsages(resourceId).hasBlockingUsage()))) {
            throw new ContestException(ContestErrorCode.CONTEST_OPERATION_BLOCKED);
        }
        return new GuardCompletionResponse(
            request.operationId(),
            complete ? "BLOCKED" : guard.previousState(),
            complete ? "COMMITTED" : "ABORTED",
            complete ? "11" : guard.sourceVersion()
        );
    }

    private GuardFixture guard(String resourceType, String resourceId) {
        return GUARDS
            .stream()
            .filter(item -> item.resourceType().equals(resourceType) && item.resourceId().equals(resourceId))
            .findFirst()
            .orElseThrow(() -> new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST));
    }

    @Override
    public StudyExamResponse getStudyExams(String studyId, String after, int size) {
        var items
            = CONTEXTS
                .stream()
                .filter(context -> studyId.equals(context.studyId()))
                .map(
                    context -> new StudyExamResponse.Item(
                        context.id(),
                        context.status(),
                        context.startsAt(),
                        context.endsAt(),
                        "1"
                    )
                )
                .toList();
        var page = page(items, "study:" + studyId, after, size);
        return new StudyExamResponse(page.items(), page.nextCursor(), page.hasMore());
    }

    @Override
    public ProblemUsageResponse getProblemUsages(String problemId) {
        var items
            = CONTEXTS
                .stream()
                .filter(ContextFixture::isBlockingUsage)
                .flatMap(
                    context -> context
                        .problems()
                        .stream()
                        .filter(problem -> problem.id().equals(problemId))
                        .map(
                            problem -> new ProblemUsageResponse.Item(
                                context.type(),
                                context.id(),
                                problem.revisionId(),
                                context.status(),
                                context.startsAt(),
                                context.endsAt()
                            )
                        )
                )
                .toList();
        return new ProblemUsageResponse(items, !items.isEmpty());
    }

    @Override
    public HintBlockResponse getHintBlocks(String memberId, String after, int size) {
        var items
            = CONTEXTS
                .stream()
                .filter(ContextFixture::isHintTarget)
                .flatMap(
                    context -> participants(context)
                        .stream()
                        .filter(item -> item.memberId().equals(memberId) && !"CANCELED".equals(item.status()))
                        .map(
                            item -> new HintBlockResponse.Item(
                                context.type(),
                                context.id(),
                                item.id(),
                                memberId,
                                "1",
                                "1",
                                context
                                    .problems()
                                    .stream()
                                    .map(InternalContestStubFixtures.ProblemFixture::id)
                                    .toList(),
                                context.startsAt(),
                                context.endsAt()
                            )
                        )
                )
                .toList();
        var page = page(items, "hint:" + memberId, after, size);
        return new HintBlockResponse(page.items(), page.nextCursor(), page.hasMore());
    }

    @Override
    public ShareableSubmissionResponse getShareableSubmission(String context, String receiptId, String memberId) {
        SubmissionFixture submission = submission(context, receiptId);
        if (!submission.memberId().equals(memberId)) {
            throw new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED);
        }
        ContextFixture parent = context(context, submission.contextId());
        if (!parent.isFinalized() || !"AC".equals(submission.verdict())) {
            throw new ContestException(ContestErrorCode.SUBMISSION_NOT_SHAREABLE);
        }
        return new ShareableSubmissionResponse(
            context,
            parent.id(),
            receiptId,
            submission.submissionId(),
            memberId,
            submission.problemId(),
            submission.revisionId(),
            "java",
            "class Solution { public int solution(int a, int b) { return a + b; } }",
            "AC",
            "1"
        );
    }

    @Override
    public FinalizedResultResponse getFinalizedResult(
        String type,
        String contextId,
        String resultRevision,
        String after,
        int size
    ) {
        ContextFixture context = context(type, contextId);
        if (!context.isFinalized()) {
            throw new ContestException(ContestErrorCode.RESULT_NOT_FINALIZED);
        }
        if (resultRevision != null && !"1".equals(resultRevision)) {
            throw new ContestException(ContestErrorCode.RESULT_REVISION_NOT_FOUND);
        }
        boolean exam = "EXAM".equals(type);
        var problems
            = context
                .problems()
                .stream()
                .map(
                    problem -> new FinalizedResultResponse.Problem(
                        problem.id(),
                        problem.revisionId(),
                        exam ? problem.score() : null
                    )
                )
                .toList();
        var items = participants(context).stream().map(item -> result(context, item)).toList();
        var page = page(items, "result:" + type + ":" + contextId + ":1", after, size);
        return new FinalizedResultResponse(
            type,
            contextId,
            "FINALIZED",
            "1",
            context.endsAt().plusMinutes(5),
            problems,
            page.items(),
            page.nextCursor(),
            page.hasMore()
        );
    }

    private FinalizedResultResponse.Item result(ContextFixture context, ParticipantFixture participant) {
        boolean exam = "EXAM".equals(context.type());
        boolean accepted = "7".equals(participant.memberId());
        boolean submitted = !"9".equals(participant.memberId());
        var results = context.problems().stream().map(problem -> {
            boolean first = "101".equals(problem.id());
            return new FinalizedResultResponse.ProblemResult(
                problem.id(),
                exam && submitted && first ? (accepted ? "AC" : "WA") : null,
                exam ? (accepted && first ? "50.00" : "0.00") : null,
                exam && submitted && first ? (accepted ? 2 : 0) : null,
                exam && submitted && first ? 2 : null,
                exam ? null : accepted && first,
                exam ? null : submitted && !accepted && first ? 1 : 0,
                !exam && accepted && first ? context.startsAt().plusMinutes(10) : null
            );
        }).toList();
        return new FinalizedResultResponse.Item(
            participant.memberId(),
            participant.id(),
            submitted,
            submitted ? accepted ? 1 : 2 : null,
            exam ? accepted ? "50.00" : "0.00" : null,
            exam ? null : accepted ? 1 : 0,
            exam ? null : accepted ? 10 : 0,
            results
        );
    }

    @Override
    public ExamRejudgeEligibilityResponse getRejudgeEligibility(String examId) {
        ContextFixture exam
            = CONTEXTS
                .stream()
                .filter(item -> "EXAM".equals(item.type()) && item.id().equals(examId))
                .findFirst()
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        return new ExamRejudgeEligibilityResponse(
            examId,
            exam.status(),
            "IN_PROGRESS".equals(exam.status()) || "CLOSED".equals(exam.status())
        );
    }

    private ContextFixture context(String type, String id) {
        return CONTEXTS
            .stream()
            .filter(item -> item.type().equals(type) && item.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new ContestException(ContestErrorCode.CONTEXT_NOT_FOUND));
    }

    private SubmissionFixture submission(String type, String receiptId) {
        return SUBMISSIONS
            .stream()
            .filter(item -> item.context().equals(type) && item.receiptId().equals(receiptId))
            .findFirst()
            .orElseThrow(() -> new ContestException(ContestErrorCode.CONTEXT_NOT_FOUND));
    }

    /**
     * 불투명 커서를 조회 종류·대상·버전에 묶어 다른 조회의 커서를 거절한다.
     * 데이터는 불변이며 실제 DB 페이지 일관성을 구현하지 않는다. (API 명세서 §4.7.5·§4.7.7·§4.7.9)
     */
    private <T> StubPage<T> page(List<T> items, String scope, String after, int size) {
        int offset = 0;
        if (after != null) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8);
                String prefix = scope + ":";
                if (!decoded.startsWith(prefix)) {
                    throw new IllegalArgumentException();
                }
                offset = Integer.parseInt(decoded.substring(prefix.length()));
                if (offset < 1 || offset >= items.size()) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
        }
        int end = Math.min(offset + size, items.size());
        boolean hasMore = end < items.size();
        String next
            = hasMore ? Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString((scope + ":" + end).getBytes(StandardCharsets.UTF_8)) : null;
        return new StubPage<>(items.subList(offset, end), next, hasMore);
    }

    /**
     * 내부 커서 슬라이스. HTTP 응답은 각 API의 DTO로 구성한다.
     */
    private record StubPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    }
}
