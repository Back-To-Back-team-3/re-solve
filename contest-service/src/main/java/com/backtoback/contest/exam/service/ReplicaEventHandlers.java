package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.MemberStatusResponse;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.exam.repository.StudyMembershipReplicaRepository;

import tools.jackson.databind.JsonNode;

/**
 * 회원과 스터디 이벤트를 소비 기록과 같은 트랜잭션에서 복제본에 반영한다.
 */
@Configuration(proxyBeanMethods = false)
public class ReplicaEventHandlers {
    private static final ThreadLocal<MemberStatusResponse> SNAPSHOT = new ThreadLocal<>();

    static void withSnapshot(MemberStatusResponse snapshot, Runnable action) {
        SNAPSHOT.set(snapshot);
        try {
            action.run();
        } finally {
            SNAPSHOT.remove();
        }
    }

    @Bean
    ReplicaHandlers replicaHandlers(
        MemberReplicaRepository members,
        StudyMembershipReplicaRepository memberships,
        Clock clock
    ) {
        return new ReplicaHandlers(List.of(handler("MemberUpdated", event -> {
            var data = event.payload();
            long memberId = id(data, "memberId");
            aggregate(event, memberId);
            members
                .sync(
                    memberId,
                    text(data, "nickname"),
                    optionalText(data, "profileImageUrl"),
                    text(data, "status"),
                    version(data),
                    now(clock)
                );
        }), handler("MemberSuspended", event -> {
            var data = event.payload();
            long memberId = id(data, "memberId");
            aggregate(event, memberId);
            long eventVersion = version(data);
            var suspended = data.get("suspended");
            if (suspended == null || !suspended.isBoolean()) {
                throw new IllegalArgumentException("suspended must be boolean");
            }
            var snapshot = SNAPSHOT.get();
            if (snapshot == null) {
                throw new IllegalStateException("Member snapshot is required");
            }
            snapshot.validate(memberId);
            String expected = suspended.asBoolean() ? "SUSPENDED" : "ACTIVE";
            if (snapshot.sourceVersion() < eventVersion
                || (snapshot.sourceVersion() == eventVersion && !expected.equals(snapshot.status()))) {
                throw new IllegalStateException("Member snapshot precedes or contradicts event");
            }
            members
                .sync(
                    memberId,
                    snapshot.nickname(),
                    snapshot.profileImageUrl(),
                    snapshot.status(),
                    snapshot.sourceVersion(),
                    now(clock)
                );
        }), handler("MemberWithdrawn", event -> {
            var data = event.payload();
            long memberId = id(data, "memberId");
            aggregate(event, memberId);
            members.sync(memberId, "탈퇴한 사용자", null, "WITHDRAWN", version(data), now(clock));
        }),
            handler("StudyMemberJoined", event -> syncMembership(event, memberships, clock, true)),
            handler("StudyMemberLeft", event -> syncMembership(event, memberships, clock, false))
        ));
    }

    @Bean
    EventHandler memberUpdatedHandler(ReplicaHandlers handlers) {
        return handlers.values().get(0);
    }

    @Bean
    EventHandler memberSuspendedHandler(ReplicaHandlers handlers) {
        return handlers.values().get(1);
    }

    @Bean
    EventHandler memberWithdrawnHandler(ReplicaHandlers handlers) {
        return handlers.values().get(2);
    }

    @Bean
    EventHandler studyJoinedHandler(ReplicaHandlers handlers) {
        return handlers.values().get(3);
    }

    @Bean
    EventHandler studyLeftHandler(ReplicaHandlers handlers) {
        return handlers.values().get(4);
    }

    private record ReplicaHandlers(List<EventHandler> values) {
    }

    private EventHandler handler(String type, java.util.function.Consumer<ReceivedEvent> action) {
        return new EventHandler() {
            public String eventType() {
                return type;
            }

            public String schemaVersion() {
                return "v1";
            }

            public void handle(ReceivedEvent event) {
                action.accept(event);
            }
        };
    }

    private void syncMembership(
        ReceivedEvent event,
        StudyMembershipReplicaRepository memberships,
        Clock clock,
        boolean active
    ) {
        var payload = event.payload();
        long membershipId = id(payload, "membershipId");
        aggregate(event, membershipId);
        memberships.sync(id(payload, "studyId"), id(payload, "memberId"), membershipId, active, now(clock));
    }

    static long id(JsonNode payload, String field) {
        String value = text(payload, field);
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 저장 가능한 양의 BIGINT 식별자만 소비한다.
        }
        throw new IllegalArgumentException("Invalid identifier: " + field);
    }

    private void aggregate(ReceivedEvent event, long id) {
        if (!Long.toString(id).equals(event.metadata().aggregateId())) {
            throw new IllegalArgumentException("Aggregate identifier does not match payload");
        }
    }

    private long version(JsonNode payload) {
        var value = payload.get("sourceVersion");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("Invalid sourceVersion");
        }
        return value.asLong();
    }

    private static String text(JsonNode payload, String field) {
        var value = payload.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value.asString();
    }

    private String optionalText(JsonNode payload, String field) {
        var value = payload.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException("Invalid field: " + field);
        }
        return value.asString();
    }

    private LocalDateTime now(Clock clock) {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
    }
}
