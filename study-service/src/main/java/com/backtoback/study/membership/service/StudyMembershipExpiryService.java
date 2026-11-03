package com.backtoback.study.membership.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembershipHistory;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;

import lombok.RequiredArgsConstructor;

/**
 * PENDING 가입 신청 1건을 만료 처리한다(스케줄러 명세서 §5.1).
 * {@code @Transactional(REQUIRES_NEW)}로 감싸 1건 실패가 다른 건의 롤백에 영향을 주지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StudyMembershipExpiryService {

    private static final DateTimeFormatter EVENT_TIME_FORMAT
        = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final String AGGREGATE_TYPE = "study_membership";
    private static final String DECIDED_EVENT_TYPE = "StudyApplicationDecided";
    private static final String SCHEMA_VERSION = "v1";

    private final StudyMembershipRepository membershipRepository;
    private final StudyMembershipHistoryRepository historyRepository;
    private final StudyRepository studyRepository;
    private final OutboxEventWriter outboxEventWriter;
    private final Clock clock;

    /**
     * 대상 신청 1건을 EXPIRED로 전이하고 이력·이벤트를 기록한다.
     * {@code expireIfPending}이 0행을 반환하면 상태가 이미 변경된 것이므로 아무 작업도 하지 않는다.
     *
     * @param membershipId 만료 처리할 가입 신청 ID
     * @param correlationId 이벤트 추적 ID (스케줄러가 건당 생성)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Long membershipId, String correlationId) {
        int updated = membershipRepository.expireIfPending(membershipId);
        if (updated == 0) {
            return;
        }

        var membership = membershipRepository.findById(membershipId).orElseThrow();
        var study = studyRepository.findById(membership.getStudyId()).orElseThrow();
        var now = LocalDateTime.now(clock);

        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        membershipId,
                        MembershipStatus.PENDING,
                        MembershipStatus.EXPIRED,
                        membership.getRole(),
                        membership.getRole(),
                        null,
                        null,
                        now
                    )
            );

        var payload
            = new StudyApplicationDecidedPayload(
                String.valueOf(study.getId()),
                study.getName(),
                String.valueOf(membershipId),
                String.valueOf(membership.getMemberId()),
                "EXPIRED",
                now.atOffset(ZoneOffset.of("+09:00")).format(EVENT_TIME_FORMAT)
            );

        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                DECIDED_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(membershipId),
                correlationId,
                payload
            );
    }
}
