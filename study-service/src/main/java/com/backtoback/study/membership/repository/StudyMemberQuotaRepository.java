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
     * 행이 없으면 active_count=0으로 생성한다. 이미 있으면 무시한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "INSERT IGNORE INTO study_member_quotas (member_id, active_count, created_at, updated_at) "
            + "VALUES (:memberId, 0, NOW(6), NOW(6))",
        nativeQuery = true
    )
    void insertQuotaIfAbsent(@Param("memberId") Long memberId);

    /**
     * active_count < 5인 경우에만 카운터를 1 올린다.
     * WHERE 조건 불충족 시 0을 반환하며, 드라이버의 CLIENT_FOUND_ROWS 설정과 무관하게 동작한다.
     * 0이면 호출자가 STUDY_JOIN_LIMIT_EXCEEDED를 던져야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE study_member_quotas " + "SET active_count = active_count + 1, updated_at = NOW(6) "
            + "WHERE member_id = :memberId AND active_count < 5",
        nativeQuery = true
    )
    int incrementIfBelowLimit(@Param("memberId") Long memberId);

    /**
     * active_count > 0인 경우에만 카운터를 1 내린다(탈퇴·취소 시 호출).
     * CLOSED 스터디 탈퇴에도 호출하며, CLOSED 여부 확인은 호출자 책임이다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE study_member_quotas SET active_count = active_count - 1, updated_at = NOW(6) "
            + "WHERE member_id = :memberId AND active_count > 0",
        nativeQuery = true
    )
    void decrementIfAboveZero(@Param("memberId") Long memberId);
}
