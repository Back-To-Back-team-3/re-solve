package com.backtoback.notification.preference.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.notification.preference.domain.NotificationPreference;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    Optional<NotificationPreference> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

    /**
     * 탈퇴한 회원의 알림 수신 설정을 물리 삭제한다 (정책 및 상태 §9.4 회원 탈퇴 전파).
     *
     * @param memberId 탈퇴한 회원 ID
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM NotificationPreference p WHERE p.memberId = :memberId")
    int deleteAllByMemberId(@Param("memberId") Long memberId);
}
