package com.backtoback.contest.exam.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 회원 상태와 표시 정보 복제본을 원천 순서에 따라 저장한다 (ERD 3.5, 4.4.17).
 */
@Repository
@RequiredArgsConstructor
public class MemberReplicaRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 유일 키 upsert에서 원천 버전을 비교해 동시 최초 저장과 늦은 스냅샷을 처리한다.
     * 탈퇴 상태는 과거 참가와 결과를 삭제하지 않고 표시 정보만 익명화한다 (이벤트 계약서 4.1 M3).
     */
    public void sync(
        long memberId,
        String nickname,
        String imageUrl,
        String status,
        long sourceVersion,
        LocalDateTime syncedAt
    ) {
        if (memberId <= 0 || sourceVersion <= 0 || nickname == null || nickname.isBlank()
            || !("ACTIVE".equals(status) || "SUSPENDED".equals(status) || "WITHDRAWN".equals(status))) {
            throw new IllegalArgumentException("Invalid member snapshot");
        }
        var displayName = "WITHDRAWN".equals(status) ? "탈퇴한 사용자" : nickname;
        var displayImage = "WITHDRAWN".equals(status) ? null : imageUrl;
        jdbcTemplate.update("""
            INSERT INTO member_replicas
                (member_id, nickname, profile_image_url, member_status, source_version,
                 synced_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                nickname = IF(VALUES(source_version) > source_version, VALUES(nickname), nickname),
                profile_image_url = IF(VALUES(source_version) > source_version,
                    VALUES(profile_image_url), profile_image_url),
                member_status = IF(VALUES(source_version) > source_version, VALUES(member_status), member_status),
                synced_at = IF(VALUES(source_version) > source_version, VALUES(synced_at), synced_at),
                updated_at = IF(VALUES(source_version) > source_version, VALUES(updated_at), updated_at),
                source_version = GREATEST(source_version, VALUES(source_version))
            """, memberId, displayName, displayImage, status, sourceVersion, syncedAt, syncedAt, syncedAt);
    }

    public Optional<String> findStatus(long memberId) {
        return jdbcTemplate
            .query(
                "SELECT member_status FROM member_replicas WHERE member_id = ?",
                (result, row) -> result.getString(1),
                memberId
            )
            .stream()
            .findFirst();
    }

    /**
     * 소비 트랜잭션에서 복제본 행을 잠그고 더 최신 정지·해제 상태만 반영한다 (이벤트 계약서 4.1 M2).
     * 표시 정보와 탈퇴 상태는 보존한다.
     *
     * @param sourceVersion 회원 이벤트의 원천 순번
     * @throws IllegalStateException 회원 정보가 아직 반영되지 않아 이벤트를 재수신해야 하는 경우
     */
    public void syncSuspension(long memberId, boolean suspended, long sourceVersion, LocalDateTime syncedAt) {
        var rows
            = jdbcTemplate
                .query(
                    "SELECT member_status, source_version FROM member_replicas WHERE member_id = ? FOR UPDATE",
                    (result, row) -> new MemberState(result.getString(1), result.getLong(2)),
                    memberId
                );
        if (rows.isEmpty()) {
            throw new IllegalStateException("Member replica is required for suspension event");
        }
        var current = rows.getFirst();
        if (sourceVersion > current.version() && !"WITHDRAWN".equals(current.status())) {
            jdbcTemplate
                .update(
                    "UPDATE member_replicas SET member_status=?, source_version=?, synced_at=?, updated_at=? "
                        + "WHERE member_id=?",
                    suspended ? "SUSPENDED" : "ACTIVE",
                    sourceVersion,
                    syncedAt,
                    syncedAt,
                    memberId
                );
        }
    }

    /** 잠금으로 읽은 회원 상태와 원천 순번이다. */
    private record MemberState(String status, long version) {
    }

    /** 시험 저장 트랜잭션이 끝날 때까지 회원 복제본의 상태 갱신을 기다리게 한다. */
    public Optional<String> lockStatus(long memberId) {
        return jdbcTemplate
            .query(
                "SELECT member_status FROM member_replicas WHERE member_id = ? FOR UPDATE",
                (result, row) -> result.getString(1),
                memberId
            )
            .stream()
            .findFirst();
    }
}
