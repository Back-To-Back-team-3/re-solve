package com.backtoback.contest.operation.service;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 가드의 작업별 종결 결과와 실제 확인 경로를 가드 변경 트랜잭션에 보존한다. */
@Component
@RequiredArgsConstructor
public class ContestGuardHistory {
    private final JdbcTemplate jdbc;

    /** 종결 결과가 다른 작업 준비로 덮여도 처음 요청과 결과를 다시 확인한다. */
    public Result find(String type, long resource, String operationId) {
        var rows
            = jdbc
                .query(
                    "SELECT request_hash,prepared_at,state,operation_result FROM contest_guard_operation_history "
                        + "WHERE resource_type=? AND resource_id=? AND operation_id=?",
                    (rs, row) -> new Result(
                        rs.getString(1),
                        rs.getObject(2, LocalDateTime.class),
                        rs.getString(3),
                        rs.getString(4)
                    ),
                    type,
                    resource,
                    operationId
                );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 직접 호출만 원래 사유를 제공하며 복구 경로는 확인한 출처만 저장한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String type, long resource, Path path, String reason, LocalDateTime resolvedAt) {
        jdbc
            .update(
                "INSERT INTO contest_guard_operation_history (resource_type,resource_id,operation_id,request_hash,"
                    + "prepared_at,state,operation_result,source_version,resolution_path,reason,resolved_at) "
                    + "SELECT resource_type,resource_id,operation_id,request_hash,prepared_at,state,operation_result,"
                    + "source_version,?,?,? FROM contest_resource_guards "
                    + "WHERE resource_type=? AND resource_id=? AND operation_id IS NOT NULL",
                path.name(),
                reason,
                resolvedAt,
                type,
                resource
            );
    }

    /** 가드 종결의 확인 경로. */
    public enum Path {
        DIRECT, RECONCILIATION, EVENT
    }

    /** 이전 준비 요청의 해시와 종결 상태. */
    public record Result(String hash, LocalDateTime preparedAt, String state, String operationResult) {
    }
}
