package com.backtoback.study.membership.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.membership.domain.StudyMemberQuota;

public interface StudyMemberQuotaRepository extends JpaRepository<StudyMemberQuota, Long> {

    Optional<StudyMemberQuota> findByMemberId(Long memberId);

    /**
     * 회원의 참여 카운터를 1 올리거나 행이 없으면 새로 만든다.
     * MySQL affected rows: 1=신규 INSERT, 2=갱신, 0=상한(5) 초과로 변경 없음.
     * 0이면 호출자가 STUDY_JOIN_LIMIT_EXCEEDED를 던져야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "INSERT INTO study_member_quotas (member_id, active_count, created_at, updated_at) "
            + "VALUES (:memberId, 1, NOW(6), NOW(6)) " + "ON DUPLICATE KEY UPDATE "
            + "active_count = IF(active_count < 5, active_count + 1, active_count), "
            + "updated_at = IF(active_count < 5, NOW(6), updated_at)",
        nativeQuery = true
    )
    int upsertActiveCount(@Param("memberId") Long memberId);
}
