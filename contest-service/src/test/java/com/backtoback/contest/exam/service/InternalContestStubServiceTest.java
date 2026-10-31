package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.contest.exam.dto.ContextValidateRequest;
import com.backtoback.contest.exam.dto.GuardFinishRequest;
import com.backtoback.contest.exam.dto.GuardPrepareRequest;
import com.backtoback.contest.global.error.ContestException;

/**
 * 고정 카탈로그의 문맥·Guard·조회 간 일관성과 명세의 분기 응답을 검증한다. (API 명세서 §4.7)
 * 실제 저장·분산 경합·업무 이벤트는 검증 대상이 아니다.
 */
@ExtendWith(MockitoExtension.class)
class InternalContestStubServiceTest {
    private final InternalContestStubService service = new InternalContestStubService();

    @ParameterizedTest
    @CsvSource(
        {
            "EXAM,55,RUN,SAMPLES_ONLY",
            "EXAM,56,READ_BODY,PROVISIONAL",
            "EXAM,57,READ_IMAGE,FINALIZED",
            "CONTEST,75,RUN,FULL_VERDICT",
            "CONTEST,76,READ_BODY,FULL_VERDICT"
        }
    )
    @DisplayName("입장한 회원에게 문맥과 상태에 맞는 결과 공개 범위를 반환한다")
    void validatesContext(String context, String id, String action, String visibility) {
        // given
        var request = contextRequest(context, id, "7", "1001", action, null);

        // when
        var response = service.validateContext(request);

        // then
        assertThat(response.allowed()).isTrue();
        assertThat(response.participantId()).isEqualTo(id + "7");
        assertThat(response.problemRevisionId()).isEqualTo("1001");
        assertThat(response.resultVisibility()).isEqualTo(visibility);
        assertThat(response.personalEndsAt()).isNotNull();
        assertThat(response.serverNow()).isEqualTo(InternalContestStubFixtures.NOW);
    }

    @ParameterizedTest
    @CsvSource(
        {
            "CONTEST,77,READ_BODY",
            "CONTEST,79,READ_IMAGE"
        }
    )
    @DisplayName("종료 공개 대회는 미등록 회원의 일반 열람을 참가 ID 없이 허용한다")
    void allowsPublicReading(String context, String id, String action) {
        // given
        var request = contextRequest(context, id, "999", "1001", action, null);

        // when
        var response = service.validateContext(request);

        // then
        assertThat(response.participantId()).isNull();
        assertThat(response.personalEndsAt()).isNull();
        assertThat(response.resultVisibility()).isEqualTo("FULL_VERDICT");
    }

    @ParameterizedTest
    @CsvSource(
        {
            "EXAM,999,7,1001,RUN,CONTEXT_NOT_FOUND",
            "EXAM,55,7,999,RUN,PROBLEM_REVISION_NOT_INCLUDED",
            "EXAM,55,8,1001,RUN,CONTEXT_ACCESS_DENIED",
            "EXAM,51,7,1001,READ_BODY,CONTEXT_ACCESS_DENIED",
            "EXAM,58,7,1001,RUN,CONTEXT_ACCESS_DENIED",
            "EXAM,56,7,1001,RUN,EXAM_CLOSED",
            "EXAM,55,9,1001,RUN,EXAM_CLOSED",
            "CONTEST,76,7,1001,RUN,CONTEST_CLOSED",
            "CONTEST,76,999,1001,READ_BODY,CONTEXT_ACCESS_DENIED",
            "CONTEST,79,999,1001,RUN,CONTEXT_ACCESS_DENIED"
        }
    )
    @DisplayName("없는 문맥·회차와 권한 거절·마감 충돌을 구분한다")
    void rejectsInvalidContext(
        String context,
        String id,
        String memberId,
        String revision,
        String action,
        String code
    ) {
        // given
        var request = contextRequest(context, id, memberId, revision, action, null);

        // when & then
        assertError(() -> service.validateContext(request), code);
    }

    @Test
    @DisplayName("제출 열람은 접수의 문맥·문제·회원 소유권을 함께 확인한다")
    void validatesSubmissionOwnership() {
        // given
        var own = contextRequest("EXAM", "55", "7", "1001", "READ_SUBMISSION", "5501");
        var otherContext = contextRequest("EXAM", "55", "7", "1001", "READ_SUBMISSION", "5701");
        var otherMember = contextRequest("EXAM", "57", "7", "1001", "READ_SUBMISSION", "5702");

        // when & then
        assertThat(service.validateContext(own).allowed()).isTrue();
        assertError(() -> service.validateContext(otherContext), "CONTEXT_ACCESS_DENIED");
        assertError(() -> service.validateContext(otherMember), "CONTEXT_ACCESS_DENIED");
    }

    @Test
    @DisplayName("미확정 실제 입장만 탈퇴를 차단하고 단순 등록은 힌트 차단에만 포함한다")
    void distinguishesAdmissionFromRegistration() {
        // given
        // when
        var active = service.getActiveParticipations("7");
        var registered = service.getActiveParticipations("8");
        var hints = service.getHintBlocks("8", null, 100);
        var finishedHints = service.getHintBlocks("9", null, 100);

        // then
        assertThat(active.withdrawalBlocked()).isTrue();
        assertThat(active.items())
            .extracting(item -> item.contextId())
            .contains("55", "56", "75", "76")
            .doesNotContain("51", "57", "58", "71", "77");
        assertThat(registered.withdrawalBlocked()).isFalse();
        assertThat(registered.items()).isEmpty();
        assertThat(hints.items()).extracting(item -> item.contextId()).containsExactly("51", "55", "71", "75");
        assertThat(finishedHints.items()).extracting(item -> item.contextId()).containsExactly("55", "75");
        assertThat(service.getActiveParticipations("999").items()).isEmpty();
        assertThat(service.getHintBlocks("999", null, 20).items()).isEmpty();
    }

    @Test
    @DisplayName("사용·힌트·문맥 조회가 같은 고정 문제 회차와 참가 ID를 사용한다")
    void keepsCatalogueConsistent() {
        // given
        var usages = service.getProblemUsages("101");
        var hints = service.getHintBlocks("7", null, 100);

        // when & then
        assertThat(usages.hasBlockingUsage()).isTrue();
        assertThat(usages.items()).allSatisfy(usage -> {
            assertThat(usage.problemRevisionId()).isEqualTo("1001");
            assertThat(usage.status()).isIn("SCHEDULED", "IN_PROGRESS", "RUNNING");
        });
        var hint = hints.items().stream().filter(item -> "55".equals(item.contextId())).findFirst().orElseThrow();
        var validated = service.validateContext(contextRequest("EXAM", "55", "7", "1001", "READ_BODY", null));
        assertThat(hint.participantId()).isEqualTo(validated.participantId());
        assertThat(hint.problemIds()).contains("101");
        assertThat(service.getProblemUsages("103").items()).isEmpty();
        assertThat(service.getProblemUsages("999").hasBlockingUsage()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(
        {
            "MEMBER,8,WITHDRAWAL",
            "STUDY,13,STUDY_CLOSE",
            "PROBLEM,103,PROBLEM_HIDE",
            "PROBLEM,104,PROBLEM_HIDE"
        }
    )
    @DisplayName("허용된 Guard 준비는 중복 호출에도 같은 PREPARED 응답을 반환한다")
    void preparesGuard(String type, String id, String operationType) {
        // given
        var request = prepareRequest(type, id, operationType);

        // when
        var first = service.prepareGuard(type, id, request);
        var repeated = service.prepareGuard(type, id, request);

        // then
        assertThat(first).isEqualTo(repeated);
        assertThat(first.state()).isEqualTo("PREPARED");
        assertThat(first.operationResult()).isEqualTo("PENDING");
        assertThat(first.ready()).isTrue();
        assertThat(first.canceledExamIds()).isEmpty();
        assertThat(first.blockingExamIds()).isEmpty();
    }

    @Test
    @DisplayName("진행 시험이 있는 스터디는 대기하고 미확정 CLOSED 시험은 준비를 막지 않는다")
    void distinguishesStudyWaiting() {
        // given
        var waiting = prepareRequest("STUDY", "12", "STUDY_CLOSE");
        var ready = prepareRequest("STUDY", "13", "STUDY_CLOSE");

        // when
        var response = service.prepareGuard("STUDY", "12", waiting);

        // then
        assertThat(response.ready()).isFalse();
        assertThat(response.canceledExamIds()).containsExactly("51");
        assertThat(response.blockingExamIds()).containsExactly("55");
        assertThat(service.prepareGuard("STUDY", "13", ready).ready()).isTrue();
        assertError(
            () -> service.completeGuard("STUDY", "12", finishRequest("STUDY", "12", "11")),
            "CONTEST_OPERATION_BLOCKED"
        );
        assertThat(service.cancelGuard("STUDY", "12", finishRequest("STUDY", "12", "10")).state()).isEqualTo("OPEN");
    }

    @ParameterizedTest
    @CsvSource(
        {
            "MEMBER,7,WITHDRAWAL,WITHDRAWAL_BLOCKED_ACTIVE_EXAM",
            "PROBLEM,101,PROBLEM_HIDE,PROBLEM_IN_ACTIVE_EXAM",
            "PROBLEM,102,PROBLEM_HIDE,PROBLEM_IN_ACTIVE_EXAM"
        }
    )
    @DisplayName("실제 입장 회원과 예정·진행 문제의 준비를 각각 명세 오류로 거절한다")
    void blocksGuard(String type, String id, String operationType, String code) {
        // given
        var request = prepareRequest(type, id, operationType);

        // when & then
        assertError(() -> service.prepareGuard(type, id, request), code);
    }

    @Test
    @DisplayName("Guard의 본문 상충·타 작업 점유·오래된 원천 버전을 구분한다")
    void rejectsConflictingGuards() {
        // given
        var differentBody = new GuardPrepareRequest("stub-problem-103", "PROBLEM_HIDE", "11");
        var occupied = new GuardPrepareRequest("another-operation", "PROBLEM_HIDE", "10");
        var stale = new GuardPrepareRequest("another-operation", "PROBLEM_HIDE", "9");
        var wrongType = new GuardPrepareRequest("stub-problem-103", "WITHDRAWAL", "10");

        // when & then
        assertError(() -> service.prepareGuard("PROBLEM", "103", differentBody), "IDEMPOTENCY_KEY_CONFLICT");
        assertError(() -> service.prepareGuard("PROBLEM", "103", occupied), "CONTEST_OPERATION_BLOCKED");
        assertError(() -> service.prepareGuard("PROBLEM", "103", stale), "SOURCE_VERSION_CONFLICT");
        assertError(() -> service.prepareGuard("PROBLEM", "103", wrongType), "IDEMPOTENCY_KEY_CONFLICT");
    }

    @ParameterizedTest
    @CsvSource(
        {
            "MEMBER,10,WITHDRAWAL,BLOCKED,COMMITTED",
            "MEMBER,11,WITHDRAWAL,OPEN,ABORTED",
            "PROBLEM,105,PROBLEM_HIDE,BLOCKED,COMMITTED",
            "PROBLEM,106,PROBLEM_HIDE,OPEN,ABORTED",
            "PROBLEM,107,PROBLEM_HIDE,BLOCKED,ABORTED"
        }
    )
    @DisplayName("종결된 고정 Guard는 prepare 재요청에도 결과를 보존하고 다시 준비하지 않는다")
    void preservesTerminalGuard(String type, String id, String operationType, String state, String result) {
        // given
        var request = prepareRequest(type, id, operationType);

        // when
        var response = service.prepareGuard(type, id, request);

        // then
        assertThat(response.state()).isEqualTo(state);
        assertThat(response.operationResult()).isEqualTo(result);
        assertThat(response.ready()).isFalse();
    }

    @Test
    @DisplayName("완료·취소 응답을 반복 재현하고 BLOCKED에서 준비한 취소는 BLOCKED로 복원한다")
    void returnsFixedCompletionAndCancellation() {
        // given
        var complete = finishRequest("PROBLEM", "103", "11");
        var cancel = finishRequest("PROBLEM", "104", "10");

        // when
        var completed = service.completeGuard("PROBLEM", "103", complete);
        var canceled = service.cancelGuard("PROBLEM", "104", cancel);

        // then
        assertThat(completed.operationResult()).isEqualTo("COMMITTED");
        assertThat(completed.state()).isEqualTo("BLOCKED");
        assertThat(completed.sourceVersion()).isEqualTo("11");
        assertThat(service.completeGuard("PROBLEM", "103", complete)).isEqualTo(completed);
        assertThat(canceled.operationResult()).isEqualTo("ABORTED");
        assertThat(canceled.state()).isEqualTo("BLOCKED");
        assertThat(canceled.sourceVersion()).isEqualTo("10");
        assertThat(service.cancelGuard("PROBLEM", "104", cancel)).isEqualTo(canceled);
        assertThat(service.completeGuard("PROBLEM", "105", finishRequest("PROBLEM", "105", "11")).operationResult())
            .isEqualTo("COMMITTED");
        assertThat(service.cancelGuard("PROBLEM", "107", finishRequest("PROBLEM", "107", "10")).state())
            .isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("완료·취소는 오래된 작업 ID·잘못된 버전·반대 종결 결과를 거절한다")
    void rejectsInvalidFinishing() {
        // given
        var old = new GuardFinishRequest("old-operation", "11", "개발 검증");

        // when & then
        assertError(() -> service.completeGuard("PROBLEM", "103", old), "OPERATION_NOT_CURRENT");
        assertError(() -> service.cancelGuard("PROBLEM", "103", old), "OPERATION_NOT_CURRENT");
        assertError(
            () -> service.completeGuard("PROBLEM", "103", finishRequest("PROBLEM", "103", "10")),
            "SOURCE_VERSION_CONFLICT"
        );
        assertError(
            () -> service.cancelGuard("PROBLEM", "103", finishRequest("PROBLEM", "103", "11")),
            "SOURCE_VERSION_CONFLICT"
        );
        assertError(
            () -> service.cancelGuard("PROBLEM", "105", finishRequest("PROBLEM", "105", "10")),
            "OPERATION_ALREADY_COMPLETED"
        );
        assertError(
            () -> service.completeGuard("PROBLEM", "106", finishRequest("PROBLEM", "106", "11")),
            "OPERATION_ALREADY_COMPLETED"
        );
    }

    @Test
    @DisplayName("스터디 시험 페이지는 CLOSED·FINALIZED를 유지하고 마지막 커서는 null이다")
    void pagesStudyExams() {
        // given
        var ids = new ArrayList<String>();
        var first = service.getStudyExams("13", null, 1);
        var page = first;

        // when
        while (true) {
            ids.addAll(page.items().stream().map(item -> item.examId()).toList());
            if (!page.hasMore()) {
                break;
            }
            page = service.getStudyExams("13", page.nextCursor(), 1);
        }

        // then
        assertThat(first.items().getFirst().status()).isEqualTo("CLOSED");
        assertThat(ids).containsExactly("56", "57", "58", "59");
        assertThat(page.nextCursor()).isNull();
        assertThat(service.getStudyExams("999", null, 20).items()).isEmpty();
    }

    @Test
    @DisplayName("힌트와 확정 결과를 누락·중복 없이 페이지로 조회하고 다른 대상 커서는 거절한다")
    void pagesHintsAndResults() {
        // given
        var hintFirst = service.getHintBlocks("7", null, 2);
        var resultFirst = service.getFinalizedResult("EXAM", "57", "1", null, 2);

        // when
        var hintLast = service.getHintBlocks("7", hintFirst.nextCursor(), 2);
        var resultLast = service.getFinalizedResult("EXAM", "57", "1", resultFirst.nextCursor(), 2);

        // then
        assertThat(hintFirst.items()).extracting(item -> item.contextId()).containsExactly("51", "55");
        assertThat(hintLast.items()).extracting(item -> item.contextId()).containsExactly("71", "75");
        assertThat(hintLast.hasMore()).isFalse();
        assertThat(hintLast.nextCursor()).isNull();
        assertThat(resultFirst.items()).extracting(item -> item.memberId()).containsExactly("7", "8");
        assertThat(resultLast.items()).extracting(item -> item.memberId()).containsExactly("9");
        assertThat(resultLast.resultRevision()).isEqualTo(resultFirst.resultRevision());
        assertThat(resultLast.nextCursor()).isNull();
        assertError(() -> service.getHintBlocks("8", hintFirst.nextCursor(), 2), "COMMON_INVALID_REQUEST");
        assertError(
            () -> service.getFinalizedResult("CONTEST", "77", "1", resultFirst.nextCursor(), 2),
            "COMMON_INVALID_REQUEST"
        );
        assertError(() -> service.getStudyExams("12", "broken", 20), "COMMON_INVALID_REQUEST");
    }

    @ParameterizedTest
    @CsvSource(
        {
            "EXAM,5701,57",
            "CONTEST,7701,77"
        }
    )
    @DisplayName("확정된 본인 AC 접수의 고정 회차·코드·결과 버전을 공유한다")
    void sharesOwnAcceptedSubmission(String context, String receipt, String id) {
        // given
        // when
        var response = service.getShareableSubmission(context, receipt, "7");
        var snapshot = service.getFinalizedResult(context, id, response.resultRevision(), null, 20);

        // then
        assertThat(response.contextId()).isEqualTo(id);
        assertThat(response.verdict()).isEqualTo("AC");
        assertThat(response.language()).isEqualTo("java");
        assertThat(response.sourceCode()).contains("class Solution");
        assertThat(snapshot.problems()).anySatisfy(problem -> {
            assertThat(problem.problemId()).isEqualTo(response.problemId());
            assertThat(problem.problemRevisionId()).isEqualTo(response.problemRevisionId());
        });
    }

    @ParameterizedTest
    @CsvSource(
        {
            "EXAM,5501,7,SUBMISSION_NOT_SHAREABLE",
            "EXAM,5601,7,SUBMISSION_NOT_SHAREABLE",
            "EXAM,5702,8,SUBMISSION_NOT_SHAREABLE",
            "CONTEST,7702,8,SUBMISSION_NOT_SHAREABLE",
            "EXAM,5701,8,CONTEXT_ACCESS_DENIED",
            "CONTEST,7701,8,CONTEXT_ACCESS_DENIED",
            "EXAM,999,7,CONTEXT_NOT_FOUND"
        }
    )
    @DisplayName("미확정·비AC·타인·없는 접수를 각각 구분해 공유를 거절한다")
    void rejectsUnshareableSubmission(String context, String receipt, String memberId, String code) {
        // given
        // when & then
        assertError(() -> service.getShareableSubmission(context, receipt, memberId), code);
    }

    @Test
    @DisplayName("시험 점수와 대회 해결·패널티의 null 필드 및 미제출·미집계 결과를 구분한다")
    void returnsContextSpecificResults() {
        // given
        // when
        var exam = service.getFinalizedResult("EXAM", "57", null, null, 20);
        var contest = service.getFinalizedResult("CONTEST", "77", null, null, 20);

        // then
        var examWinner = exam.items().getFirst();
        assertThat(examWinner.score()).isEqualTo("50.00");
        assertThat(examWinner.solvedCount()).isNull();
        assertThat(examWinner.problems().getFirst().passedCount()).isEqualTo(2);
        assertThat(examWinner.problems().getLast().verdict()).isNull();
        assertThat(examWinner.problems().getLast().passedCount()).isNull();
        assertThat(exam.items().getLast().isRanked()).isFalse();
        assertThat(exam.items().getLast().rank()).isNull();
        var contestWinner = contest.items().getFirst();
        assertThat(contestWinner.score()).isNull();
        assertThat(contestWinner.solvedCount()).isEqualTo(1);
        assertThat(contestWinner.penaltyMinutes()).isEqualTo(10);
        assertThat(contestWinner.problems().getFirst().isSolved()).isTrue();
        assertThat(contestWinner.problems().getFirst().acceptedAt()).isNotNull();
        assertThat(contestWinner.problems().getLast().isSolved()).isFalse();
        assertThat(contestWinner.problems().getLast().acceptedAt()).isNull();
        assertThat(contest.problems()).allSatisfy(problem -> assertThat(problem.score()).isNull());
    }

    @Test
    @DisplayName("없는 문맥·미확정·없는 결과 버전·참가자 없는 확정 결과를 구분한다")
    void distinguishesResultFailuresAndEmptySnapshots() {
        // given
        // when & then
        assertError(() -> service.getFinalizedResult("EXAM", "999", null, null, 20), "CONTEXT_NOT_FOUND");
        assertError(() -> service.getFinalizedResult("EXAM", "56", null, null, 20), "RESULT_NOT_FINALIZED");
        assertError(() -> service.getFinalizedResult("CONTEST", "76", null, null, 20), "RESULT_NOT_FINALIZED");
        assertError(() -> service.getFinalizedResult("EXAM", "57", "99", null, 20), "RESULT_REVISION_NOT_FOUND");
        assertThat(service.getFinalizedResult("EXAM", "59", null, null, 20).items()).isEmpty();
        assertThat(service.getFinalizedResult("CONTEST", "80", null, null, 20).items()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(
        {
            "51,SCHEDULED,false",
            "55,IN_PROGRESS,true",
            "56,CLOSED,true",
            "57,FINALIZED,false",
            "58,CANCELED,false"
        }
    )
    @DisplayName("재채점 가능 여부는 부모 시험의 다섯 상태를 그대로 구분한다")
    void returnsRejudgeEligibility(String id, String status, boolean eligible) {
        // given
        // when
        var response = service.getRejudgeEligibility(id);

        // then
        assertThat(response.examId()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.eligible()).isEqualTo(eligible);
    }

    @Test
    @DisplayName("재채점 조회는 없는 시험과 대회 ID를 시험 없음으로 응답한다")
    void rejectsMissingExam() {
        // given
        // when & then
        assertError(() -> service.getRejudgeEligibility("999"), "EXAM_NOT_FOUND");
        assertError(() -> service.getRejudgeEligibility("75"), "EXAM_NOT_FOUND");
    }

    private ContextValidateRequest contextRequest(
        String context,
        String id,
        String memberId,
        String revision,
        String action,
        String receipt
    ) {
        return new ContextValidateRequest(context, id, memberId, "101", revision, action, receipt);
    }

    private GuardPrepareRequest prepareRequest(String type, String id, String operationType) {
        return new GuardPrepareRequest(
            "stub-" + type.toLowerCase(java.util.Locale.ROOT) + "-" + id,
            operationType,
            "10"
        );
    }

    private GuardFinishRequest finishRequest(String type, String id, String version) {
        return new GuardFinishRequest("stub-" + type.toLowerCase(java.util.Locale.ROOT) + "-" + id, version, "개발 검증");
    }

    private void assertError(Runnable action, String code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(
                ContestException.class,
                exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo(code)
            );
    }
}
