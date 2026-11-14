package com.backtoback.contest.exam.repository;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 시험 참가 자격을 위한 스터디 가입 복제본을 저장한다 (ERD 4.4.13).
 */
@Repository
@RequiredArgsConstructor
public class StudyMembershipReplicaRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 유일 키의 원자적 upsert로 동시 최초 수신과 순서 역전을 처리한다.
     * 같은 가입의 탈퇴를 보존하고 더 큰 가입 ID만 새 가입으로 반영한다 (이벤트 계약서 1.5).
     */
    public void sync(long studyId, long memberId, long membershipId, boolean active, LocalDateTime syncedAt) {
        jdbcTemplate.update("""
            INSERT INTO study_membership_replicas
                (study_id, member_id, membership_id, is_active, synced_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                synced_at = IF(VALUES(membership_id) > membership_id OR
                    (VALUES(membership_id) = membership_id AND is_active = TRUE AND VALUES(is_active) = FALSE),
                    VALUES(synced_at), synced_at),
                updated_at = IF(VALUES(membership_id) > membership_id OR
                    (VALUES(membership_id) = membership_id AND is_active = TRUE AND VALUES(is_active) = FALSE),
                    VALUES(updated_at), updated_at),
                is_active = IF(VALUES(membership_id) > membership_id OR
                    (VALUES(membership_id) = membership_id AND VALUES(is_active) = FALSE),
                    VALUES(is_active), is_active),
                membership_id = GREATEST(membership_id, VALUES(membership_id))
            """, studyId, memberId, membershipId, active, syncedAt, syncedAt, syncedAt);
    }

    public boolean isActive(long studyId, long memberId) {
        Integer count
            = jdbcTemplate
                .queryForObject(
                    "SELECT COUNT(*) FROM study_membership_replicas "
                        + "WHERE study_id = ? AND member_id = ? AND is_active = TRUE",
                    Integer.class,
                    studyId,
                    memberId
                );
        return count != null && count > 0;
    }
}
