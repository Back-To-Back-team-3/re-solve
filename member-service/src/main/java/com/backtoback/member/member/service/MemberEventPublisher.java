package com.backtoback.member.member.service;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.backtoback.common.event.service.OutboxEventWriter;
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
    static final String SCHEMA_VERSION = "v1";

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

    // HTTP 요청은 CorrelationIdFilter가 MDC에 넣은 값을, 그 밖의 흐름은 새 ID를 쓴다.
    private static String correlationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId != null ? correlationId : "member-" + UUID.randomUUID();
    }
}
