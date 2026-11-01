package com.backtoback.study.membership.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyMembershipHistory;
import com.backtoback.study.membership.dto.StudyCreateRequest;
import com.backtoback.study.membership.repository.StudyMemberQuotaRepository;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 생성·가입 명령 서비스 (API 명세서 §5.2).
 */
@Service
@RequiredArgsConstructor
public class StudyCommandService {

    private static final DateTimeFormatter EVENT_TIME_FORMAT
        = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final String AGGREGATE_TYPE = "study_membership";
    private static final String EVENT_TYPE = "StudyMemberJoined";
    private static final String SCHEMA_VERSION = "v1";

    private final MemberReplicaRepository memberReplicaRepository;
    private final StudyMemberQuotaRepository quotaRepository;
    private final StudyRepository studyRepository;
    private final StudyMembershipRepository membershipRepository;
    private final StudyMembershipHistoryRepository historyRepository;
    private final OutboxEventWriter outboxEventWriter;
    private final Clock clock;

    /**
     * 스터디를 생성하고 생성자를 LEADER(APPROVED)로 등록한다(API 명세서 §5.2.1).
     * 참여 상한 확보와 S1 이벤트 발행은 같은 트랜잭션에서 처리한다.
     *
     * @param memberId 스터디를 만드는 회원 ID
     * @param request 생성 요청 바디
     * @param correlationId 원 요청의 추적 ID (이벤트 계약서 §1.1)
     * @return 생성된 스터디 ID
     */
    @Transactional
    public Long createStudy(Long memberId, StudyCreateRequest request, String correlationId) {
        // SUSPENDED 차단: 복제본이 없으면 확인 생략(이벤트 동기화 지연 허용)
        memberReplicaRepository.findByMemberId(memberId).ifPresent(replica -> {
            if (replica.getMemberStatus() == MemberStatus.SUSPENDED) {
                throw new StudyException(StudyErrorCode.MEMBER_SUSPENDED);
            }
        });

        // ① 참여 상한 카운터 upsert: 신규→1, 갱신→2, 상한 초과(변경 없음)→0
        int affected = quotaRepository.upsertActiveCount(memberId);
        if (affected == 0) {
            throw new StudyException(StudyErrorCode.STUDY_JOIN_LIMIT_EXCEEDED);
        }

        // ② 스터디 INSERT (member_count = 1)
        Study study
            = Study
                .recruit(
                    request.name(),
                    request.description(),
                    request.goal(),
                    request.skillLevel(),
                    request.preferredLanguage(),
                    request.activeTimeSlot(),
                    request.capacity(),
                    request.joinType(),
                    request.isAutoReopen()
                );
        study.addMember();
        studyRepository.save(study);

        // ③ 가입 INSERT (LEADER, APPROVED)
        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        StudyMembership membership
            = StudyMembership
                .apply(study.getId(), memberId, MembershipRole.LEADER, MembershipStatus.APPROVED, now, null, now);
        membershipRepository.save(membership);

        // ④ 이력 INSERT
        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        membership.getId(),
                        null,
                        MembershipStatus.APPROVED,
                        null,
                        MembershipRole.LEADER,
                        memberId,
                        null,
                        now
                    )
            );

        // ⑤ Outbox: S1 StudyMemberJoined (이벤트 계약서 §4.5 S1)
        String joinedAt = now.atOffset(ZoneOffset.of("+09:00")).format(EVENT_TIME_FORMAT);
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(membership.getId()),
                correlationId,
                new StudyMemberJoinedPayload(
                    String.valueOf(study.getId()),
                    String.valueOf(membership.getId()),
                    String.valueOf(memberId),
                    MembershipRole.LEADER.name(),
                    joinedAt
                )
            );

        return study.getId();
    }
}
