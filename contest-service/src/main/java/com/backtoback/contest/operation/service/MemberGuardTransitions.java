package com.backtoback.contest.operation.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.operation.client.MemberOperationClient.Result;

import lombok.RequiredArgsConstructor;

/** 원격 작업 결과를 현재 회원 가드에 조건부 반영한다 (API 명세서 §4.7.4). */
@Service
@RequiredArgsConstructor
public class MemberGuardTransitions {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 탈퇴 이벤트는 원천 버전과 차단을 보존하되 작업 성공으로 추정하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void observeWithdrawal(long memberId, long version) {
        jdbc
            .update(
                "INSERT INTO contest_resource_guards (resource_type,resource_id,state,source_version,created_at,"
                    + "updated_at) VALUES ('MEMBER',?,'BLOCKED',?,?,?) ON DUPLICATE KEY UPDATE "
                    + "state=IF(source_version<VALUES(source_version) AND state<>'PREPARED','BLOCKED',state),"
                    + "updated_at=IF(source_version<VALUES(source_version),VALUES(updated_at),updated_at),"
                    + "version=IF(source_version<VALUES(source_version),version+1,version),"
                    + "source_version=GREATEST(source_version,VALUES(source_version))",
                memberId,
                version,
                LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE),
                LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE)
            );
    }

    /** 조회 후 교체된 작업이나 원천 버전은 이전 응답으로 덮어쓰지 않는다. */
    @Transactional
    public boolean apply(long memberId, String operationId, long expectedVersion, Result result) {
        if (!operationId.equals(result.operationId()) || "PENDING".equals(result.state())) {
            return false;
        }
        long version = Long.parseLong(result.sourceVersion());
        var rows
            = jdbc
                .query(
                    "SELECT operation_id,operation_type,expected_source_version,state,"
                        + "prepared_from_state,operation_result,source_version FROM contest_resource_guards "
                        + "WHERE resource_type='MEMBER' AND resource_id=? FOR UPDATE",
                    (rs, row) -> new Guard(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getObject(3, Long.class),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getLong(7)
                    ),
                    memberId
                );
        if (rows.isEmpty()) {
            return false;
        }
        var guard = rows.getFirst();
        if (!operationId.equals(guard.operationId()) || !"WITHDRAWAL".equals(guard.operationType())
            || guard.expectedVersion() == null || guard.expectedVersion() != expectedVersion
            || !"PREPARED".equals(guard.state()) || !"PENDING".equals(guard.result())) {
            return false;
        }
        String next;
        if ("COMMITTED".equals(result.state()) && "WITHDRAWN".equals(result.resourceStatus())
            && version > expectedVersion) {
            next = "BLOCKED";
        } else if ("ABORTED".equals(result.state()) && version == expectedVersion
            && ("OPEN".equals(guard.previousState()) || "BLOCKED".equals(guard.previousState()))) {
            next = guard.sourceVersion() > expectedVersion ? "BLOCKED" : guard.previousState();
        } else {
            return false;
        }
        jdbc
            .update(
                "UPDATE contest_resource_guards SET state=?,operation_result=?,source_version=?,"
                    + "version=version+1,updated_at=? WHERE resource_type='MEMBER' AND resource_id=?",
                next,
                result.state(),
                Math.max(guard.sourceVersion(), version),
                LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE),
                memberId
            );
        return true;
    }

    private record Guard(
        String operationId,
        String operationType,
        Long expectedVersion,
        String state,
        String previousState,
        String result,
        long sourceVersion
    ) {
    }
}
