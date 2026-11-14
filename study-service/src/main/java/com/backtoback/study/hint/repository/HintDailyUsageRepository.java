package com.backtoback.study.hint.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.hint.domain.HintDailyUsage;

public interface HintDailyUsageRepository extends JpaRepository<HintDailyUsage, Long> {

    @Modifying
    @Query(
        nativeQuery = true,
        value = "UPDATE hint_daily_usages" + " SET used_count = used_count + 1, updated_at = now(6)"
            + " WHERE member_id = :memberId AND usage_date = :usageDate AND used_count < :limit"
    )
    int incrementIfBelowLimit(
        @Param("memberId") Long memberId,
        @Param("usageDate") LocalDate usageDate,
        @Param("limit") int limit
    );

    @Modifying
    @Query(
        nativeQuery = true,
        value = "INSERT IGNORE INTO hint_daily_usages (member_id, usage_date, used_count, created_at, updated_at)"
            + " VALUES (:memberId, :usageDate, 0, now(6), now(6))"
    )
    void insertIgnore(@Param("memberId") Long memberId, @Param("usageDate") LocalDate usageDate);

    @Query("SELECT d.usedCount FROM HintDailyUsage d" + " WHERE d.memberId = :memberId AND d.usageDate = :usageDate")
    Optional<Integer> findUsedCountByMemberIdAndUsageDate(
        @Param("memberId") Long memberId,
        @Param("usageDate") LocalDate usageDate
    );
}
