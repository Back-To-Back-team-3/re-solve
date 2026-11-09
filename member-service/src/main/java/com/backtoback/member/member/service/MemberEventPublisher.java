package com.backtoback.member.member.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.member.member.domain.Member;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트를 업무 트랜잭션 안에서 Outbox에 기록한다 (이벤트 계약 4.1). 호출하는 쪽에 트랜잭션이 있어야 한다.
 */
@Component
@RequiredArgsConstructor
public class MemberEventPublisher {

    static final String AGGREGATE_TYPE = "MEMBER";
    static final String MEMBER_UPDATED = "MemberUpdated";
    static final String MEMBER_SUSPENDED = "MemberSuspended";
    static final String LEARNING_PROFILE_UPDATED = "LearningProfileUpdated";
    static final String MEMBER_WITHDRAWN = "MemberWithdrawn";
    static final String SCHEMA_VERSION = "v1";
    // 이벤트 계약 공통 규칙: +09:00, 마이크로초 6자리 (occurredAt과 같은 형식)
    private static final DateTimeFormatter EVENT_TIME_FORMAT
        = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    private final OutboxEventWriter outboxEventWriter;

    /**
     * 가입·닉네임·이미지 변경 (M1). 변경을 반영한 뒤의 {@code profile_version}을 {@code sourceVersion}으로 싣는다.
     */
    public void memberUpdated(Member member) {
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                MEMBER_UPDATED,
                SCHEMA_VERSION,
                String.valueOf(member.getId()),
                correlationId(),
                MemberUpdatedPayload.from(member)
            );
    }

    /**
     * 관리자 정지·해제 (M2). {@code suspended}는 바뀐 뒤의 상태, {@code sourceVersion}은 M1과 같은 {@code profile_version}이다.
     */
    public void memberSuspended(Member member, String reason, LocalDateTime changedAt) {
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                MEMBER_SUSPENDED,
                SCHEMA_VERSION,
                String.valueOf(member.getId()),
                correlationId(),
                MemberSuspendedPayload
                    .of(member, reason, EVENT_TIME_FORMAT.format(changedAt.atZone(ClockConfiguration.SERVICE_ZONE)))
            );
    }

    /**
     * 학습 프로필 전체 상태 (M4). 프로필 항목 변경과 태그 레벨 변경 모두 같은 {@code profileVersion} 계열로 보낸다.
     */
    public void learningProfileUpdated(LearningProfileSnapshot snapshot) {
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                LEARNING_PROFILE_UPDATED,
                SCHEMA_VERSION,
                String.valueOf(snapshot.memberId()),
                correlationId(),
                LearningProfileUpdatedPayload.from(snapshot)
            );
    }

    /**
     * 탈퇴 (M3). {@code sourceVersion}은 M1과 같은 {@code profile_version} 계열이다.
     */
    public void memberWithdrawn(Member member) {
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                MEMBER_WITHDRAWN,
                SCHEMA_VERSION,
                String.valueOf(member.getId()),
                correlationId(),
                new MemberWithdrawnPayload(
                    String.valueOf(member.getId()),
                    EVENT_TIME_FORMAT.format(member.getWithdrawnAt().atZone(ClockConfiguration.SERVICE_ZONE)),
                    member.getProfileVersion()
                )
            );
    }

    // HTTP 요청은 CorrelationIdFilter가 MDC에 넣은 값을, 그 밖의 흐름은 새 ID를 쓴다.
    private static String correlationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId != null ? correlationId : "member-" + UUID.randomUUID();
    }
}
