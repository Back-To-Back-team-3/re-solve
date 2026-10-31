package com.backtoback.contest.exam.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 내부 HTTP 연동용 고정 개발 데이터. 시간은 벽시계와 무관한 시나리오 기준이며 실제 회원·업무 상태가 아니다.
 * 조회·Guard·공유·결과가 같은 문맥·문제·참가를 참조한다. (API 명세서 §4.7)
 */
final class InternalContestStubFixtures {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-20T20:15:00+09:00");
    static final OffsetDateTime START = NOW.minusMinutes(15);
    static final OffsetDateTime END = START.plusMinutes(90);
    static final List<ProblemFixture> PROBLEMS
        = List.of(new ProblemFixture("101", "1001", "50.00"), new ProblemFixture("102", "1002", "50.00"));
    static final List<ContextFixture> CONTEXTS
        = List
            .of(
                exam("51", "12", "SCHEDULED"),
                exam("55", "12", "IN_PROGRESS"),
                exam("56", "13", "CLOSED"),
                exam("57", "13", "FINALIZED"),
                exam("58", "13", "CANCELED"),
                exam("59", "13", "FINALIZED"),
                contest("71", "SCHEDULED", false),
                contest("75", "RUNNING", false),
                contest("76", "ENDED", false),
                contest("77", "FINALIZED", true),
                contest("78", "CANCELED", false),
                contest("79", "ENDED", true),
                contest("80", "FINALIZED", true)
            );
    static final List<SubmissionFixture> SUBMISSIONS
        = List
            .of(
                new SubmissionFixture("EXAM", "55", "5501", "9501", "7", "101", "1001", "AC"),
                new SubmissionFixture("EXAM", "56", "5601", "9601", "7", "101", "1001", "AC"),
                new SubmissionFixture("EXAM", "57", "5701", "9701", "7", "101", "1001", "AC"),
                new SubmissionFixture("EXAM", "57", "5702", "9702", "8", "101", "1001", "WA"),
                new SubmissionFixture("CONTEST", "75", "7501", "9751", "7", "101", "1001", "AC"),
                new SubmissionFixture("CONTEST", "77", "7701", "9771", "7", "101", "1001", "AC"),
                new SubmissionFixture("CONTEST", "77", "7702", "9772", "8", "101", "1001", "WA")
            );
    static final List<GuardFixture> GUARDS
        = List
            .of(
                new GuardFixture("MEMBER", "7", "PREPARED", "PENDING", "OPEN"),
                new GuardFixture("MEMBER", "8", "PREPARED", "PENDING", "OPEN"),
                new GuardFixture("MEMBER", "10", "BLOCKED", "COMMITTED", "OPEN"),
                new GuardFixture("MEMBER", "11", "OPEN", "ABORTED", "OPEN"),
                new GuardFixture("STUDY", "12", "PREPARED", "PENDING", "OPEN"),
                new GuardFixture("STUDY", "13", "PREPARED", "PENDING", "OPEN"),
                new GuardFixture("PROBLEM", "101", "PREPARED", "PENDING", "OPEN"),
                new GuardFixture("PROBLEM", "102", "PREPARED", "PENDING", "OPEN"),
                new GuardFixture("PROBLEM", "103", "PREPARED", "PENDING", "OPEN"),
                new GuardFixture("PROBLEM", "104", "PREPARED", "PENDING", "BLOCKED"),
                new GuardFixture("PROBLEM", "105", "BLOCKED", "COMMITTED", "OPEN"),
                new GuardFixture("PROBLEM", "106", "OPEN", "ABORTED", "OPEN"),
                new GuardFixture("PROBLEM", "107", "BLOCKED", "ABORTED", "BLOCKED")
            );

    private InternalContestStubFixtures() {}

    private static ContextFixture exam(String id, String studyId, String status) {
        return new ContextFixture("EXAM", id, studyId, status, false, times(status), PROBLEMS);
    }

    private static ContextFixture contest(String id, String status, boolean publicAfterEnd) {
        return new ContextFixture("CONTEST", id, null, status, publicAfterEnd, times(status), PROBLEMS);
    }

    private static List<OffsetDateTime> times(String status) {
        if ("SCHEDULED".equals(status)) {
            return List.of(START.plusDays(1), END.plusDays(1));
        }
        if ("IN_PROGRESS".equals(status) || "RUNNING".equals(status)) {
            return List.of(START, END);
        }
        return List.of(START.minusDays(1), END.minusDays(1));
    }

    static List<ParticipantFixture> participants(ContextFixture context) {
        if ("59".equals(context.id()) || "80".equals(context.id()) || "CANCELED".equals(context.status())) {
            return List.of();
        }
        if ("SCHEDULED".equals(context.status())) {
            return List
                .of(participant(context, "7", "REGISTERED", null), participant(context, "8", "REGISTERED", null));
        }
        boolean running = context.isRunning();
        return List
            .of(
                participant(context, "7", running ? "STARTED" : "FINISHED", context.startsAt().plusMinutes(1)),
                participant(
                    context,
                    "8",
                    running ? "REGISTERED" : context.isFinalized() ? "FINISHED" : "ABSENT",
                    context.isFinalized() ? context.startsAt().plusMinutes(2) : null
                ),
                participant(
                    context,
                    "9",
                    running ? "FINISHED" : "ABSENT",
                    running ? context.startsAt().plusMinutes(3) : null
                )
            );
    }

    private static ParticipantFixture participant(
        ContextFixture context,
        String memberId,
        String status,
        OffsetDateTime enteredAt
    ) {
        return new ParticipantFixture(memberId, context.id() + memberId, status, enteredAt);
    }

    /**
     * 공통 카탈로그의 고정 문제·회차·배점.
     */
    record ProblemFixture(String id, String revisionId, String score) {
    }

    /**
     * 각 내부 조회에 공통으로 사용하는 문맥의 고정 상태.
     */
    record ContextFixture(
        String type,
        String id,
        String studyId,
        String status,
        boolean publicAfterEnd,
        List<OffsetDateTime> times,
        List<ProblemFixture> problems
    ) {
        OffsetDateTime startsAt() {
            return times.getFirst();
        }

        OffsetDateTime endsAt() {
            return times.getLast();
        }

        boolean isRunning() {
            return "IN_PROGRESS".equals(status) || "RUNNING".equals(status);
        }

        boolean isFinalized() {
            return "FINALIZED".equals(status);
        }

        boolean isBlockingUsage() {
            return "SCHEDULED".equals(status) || isRunning();
        }

        boolean isHintTarget() {
            return isBlockingUsage();
        }

        String visibility() {
            if ("CONTEST".equals(type)) {
                return "FULL_VERDICT";
            }
            return isFinalized() ? "FINALIZED" : "CLOSED".equals(status) ? "PROVISIONAL" : "SAMPLES_ONLY";
        }
    }

    /**
     * 실제 입장과 단순 등록·개인 종료를 구분하는 참가 데이터.
     */
    record ParticipantFixture(String memberId, String id, String status, OffsetDateTime enteredAt) {
    }

    /**
     * 본인 제출 열람·공유에 사용하는 불변 접수 데이터.
     */
    record SubmissionFixture(
        String context,
        String contextId,
        String receiptId,
        String submissionId,
        String memberId,
        String problemId,
        String revisionId,
        String verdict
    ) {
    }

    /**
     * 저장하지 않는 Guard 응답 시나리오. 반복 호출은 이 고정 상태를 기준으로 계산한다.
     */
    record GuardFixture(String resourceType, String resourceId, String state, String result, String previousState) {
        String operationId() {
            return "stub-" + resourceType.toLowerCase(Locale.ROOT) + "-" + resourceId;
        }

        boolean isPending() {
            return "PENDING".equals(result);
        }

        String sourceVersion() {
            return "COMMITTED".equals(result) ? "11" : "10";
        }
    }
}
