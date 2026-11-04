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
}
