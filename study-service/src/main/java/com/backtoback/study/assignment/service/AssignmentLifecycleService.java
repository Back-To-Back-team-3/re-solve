package com.backtoback.study.assignment.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.repository.AssignmentRepository;
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
    private static final String SCHEMA_VERSION = "v1";
    private static final ZoneOffset SERVICE_OFFSET = ZoneOffset.of("+09:00");

    private final AssignmentRepository assignmentRepository;
    private final StudyMembershipRepository membershipRepository;
    private final AssignmentEnrollmentService enrollmentService;
    private final OutboxEventWriter outboxEventWriter;
    private final Clock clock;

    /**
     * SCHEDULED 문제집 1건을 OPEN으로 전이하고 AssignmentPublished 이벤트를 기록한다.
     * openIfScheduled가 0행이면 다른 인스턴스가 먼저 처리한 것이므로 종료한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void openTransition(Long assignmentId, String correlationId) {
        int updated = assignmentRepository.openIfScheduled(assignmentId);
        if (updated == 0) {
            return;
        }

        Assignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        List<StudyMembership> memberships
            = membershipRepository.findByStudyIdAndStatusWithLock(assignment.getStudyId(), MembershipStatus.APPROVED);

        for (StudyMembership membership : memberships) {
            enrollmentService.enrollOpenForScheduler(assignment, membership.getMemberId(), membership.getId(), now);
        }

        List<Long> targetMemberIds = memberships.stream().map(StudyMembership::getMemberId).toList();

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
    }
}
