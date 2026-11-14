package com.backtoback.study.assignment.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.MemberProgressStatus;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMembershipRepository;

import lombok.RequiredArgsConstructor;

/**
 * 문제집 상태 전이 1건을 처리한다(스케줄러 명세서 §5.2).
 * 각 메서드는 REQUIRES_NEW 트랜잭션으로 감싸 1건 실패가 다른 건의 롤백에 영향을 주지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AssignmentLifecycleService {

    private static final DateTimeFormatter EVENT_TIME_FORMAT
        = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final String AGGREGATE_TYPE = "assignment";
    private static final String PUBLISHED_EVENT_TYPE = "AssignmentPublished";
    private static final String INCOMPLETE_REPEATED_EVENT_TYPE = "AssignmentIncompleteRepeated";
    private static final String SCHEMA_VERSION = "v1";
    private static final ZoneOffset SERVICE_OFFSET = ZoneOffset.of("+09:00");

    private final AssignmentRepository assignmentRepository;
    private final AssignmentMemberStatusRepository memberStatusRepository;
    private final StudyMembershipRepository membershipRepository;
    private final AssignmentEnrollmentService enrollmentService;
    private final OutboxEventWriter outboxEventWriter;
    private final Clock clock;

    /**
     * SCHEDULED 문제집 1건을 OPEN으로 전이하고 AssignmentPublished 이벤트를 기록한다.
     * openIfScheduled가 0행이면 다른 인스턴스가 먼저 처리한 것이므로 false를 반환한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean openTransition(Long assignmentId, String correlationId) {
        int updated = assignmentRepository.openIfScheduled(assignmentId);
        if (updated == 0) {
            return false;
        }

        Assignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        List<StudyMembership> memberships
            = membershipRepository.findByStudyIdAndStatusWithLock(assignment.getStudyId(), MembershipStatus.APPROVED);

        for (StudyMembership membership : memberships) {
            enrollmentService.enrollOpenForScheduler(assignment, membership.getMemberId(), membership.getId(), now);
        }

        List<String> targetMemberIds = memberships.stream().map(m -> String.valueOf(m.getMemberId())).toList();

        String deadlineAtStr
            = assignment.getDeadlineAt() != null
                ? assignment.getDeadlineAt().atOffset(SERVICE_OFFSET).format(EVENT_TIME_FORMAT) : null;

        AssignmentPublishedPayload payload
            = new AssignmentPublishedPayload(
                String.valueOf(assignment.getStudyId()),
                String.valueOf(assignmentId),
                assignment.getTitle(),
                assignment.getStartsAt().atOffset(SERVICE_OFFSET).format(EVENT_TIME_FORMAT),
                deadlineAtStr,
                targetMemberIds
            );

        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                PUBLISHED_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(assignmentId),
                correlationId,
                payload
            );
        return true;
    }

    /**
     * OPEN 문제집 1건을 CLOSED로 전이하고 미완료 멤버를 INCOMPLETE 처리한다.
     * 연속 2회 INCOMPLETE 멤버가 있으면 AssignmentIncompleteRepeated 이벤트를 기록한다.
     * 선행 마감 실패를 대비해 A 직후 CLOSED 문제집(B)도 소급 재판정한다(§5.2 C6).
     * closeIfOpen이 0행이면 다른 인스턴스가 먼저 처리한 것이므로 false를 반환한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean closeTransition(Long assignmentId, String correlationId) {
        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        int updated = assignmentRepository.closeIfOpen(assignmentId, now);
        if (updated == 0) {
            return false;
        }

        Assignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();

        memberStatusRepository.updateToIncompleteExcludingLeft(assignmentId);

        List<Long> incompleteMemberIds
            = memberStatusRepository
                .findMemberIdsByAssignmentIdAndStatus(assignmentId, MemberProgressStatus.INCOMPLETE);
        if (incompleteMemberIds.isEmpty()) {
            return true;
        }

        Optional<Long> previousClosedId
            = assignmentRepository
                .findPreviousClosedId(assignment.getStudyId(), assignment.getDeadlineAt(), assignmentId);
        if (previousClosedId.isPresent()) {
            List<Long> repeatedMemberIds
                = memberStatusRepository
                    .findMemberIdsByAssignmentIdAndMemberIdsAndStatus(
                        previousClosedId.get(),
                        incompleteMemberIds,
                        MemberProgressStatus.INCOMPLETE
                    );
            if (!repeatedMemberIds.isEmpty()) {
                List<Long> recipientMemberIds
                    = membershipRepository
                        .findMemberIdsByStudyIdAndRolesAndStatus(
                            assignment.getStudyId(),
                            List.of(MembershipRole.LEADER, MembershipRole.MANAGER),
                            MembershipStatus.APPROVED
                        );
                AssignmentIncompleteRepeatedPayload payload
                    = new AssignmentIncompleteRepeatedPayload(
                        String.valueOf(assignment.getStudyId()),
                        String.valueOf(assignmentId),
                        repeatedMemberIds.stream().map(String::valueOf).toList(),
                        recipientMemberIds.stream().map(String::valueOf).toList()
                    );
                outboxEventWriter
                    .append(
                        AGGREGATE_TYPE,
                        INCOMPLETE_REPEATED_EVENT_TYPE,
                        SCHEMA_VERSION,
                        String.valueOf(assignmentId),
                        correlationId,
                        payload
                    );
            }
        }

        appendRetroactiveEventIfNeeded(assignment, assignmentId, correlationId);
        return true;
    }

    private void appendRetroactiveEventIfNeeded(Assignment assignment, Long assignmentId, String correlationId) {
        if (assignment.getDeadlineAt() == null) {
            return;
        }
        Optional<Long> nextClosedId
            = assignmentRepository.findNextClosedId(assignment.getStudyId(), assignment.getDeadlineAt(), assignmentId);
        if (nextClosedId.isEmpty()) {
            return;
        }
        List<Long> bIncompleteMemberIds
            = memberStatusRepository
                .findMemberIdsByAssignmentIdAndStatus(nextClosedId.get(), MemberProgressStatus.INCOMPLETE);
        if (bIncompleteMemberIds.isEmpty()) {
            return;
        }
        List<Long> retroRepeatedMemberIds
            = memberStatusRepository
                .findMemberIdsByAssignmentIdAndMemberIdsAndStatus(
                    assignmentId,
                    bIncompleteMemberIds,
                    MemberProgressStatus.INCOMPLETE
                );
        if (retroRepeatedMemberIds.isEmpty()) {
            return;
        }
        List<Long> recipientMemberIds
            = membershipRepository
                .findMemberIdsByStudyIdAndRolesAndStatus(
                    assignment.getStudyId(),
                    List.of(MembershipRole.LEADER, MembershipRole.MANAGER),
                    MembershipStatus.APPROVED
                );
        AssignmentIncompleteRepeatedPayload retroPayload
            = new AssignmentIncompleteRepeatedPayload(
                String.valueOf(assignment.getStudyId()),
                String.valueOf(nextClosedId.get()),
                retroRepeatedMemberIds.stream().map(String::valueOf).toList(),
                recipientMemberIds.stream().map(String::valueOf).toList()
            );
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                INCOMPLETE_REPEATED_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(nextClosedId.get()),
                correlationId,
                retroPayload
            );
    }
}
