package com.backtoback.notification.notification.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByMemberId(Long memberId, Pageable pageable);

    Page<Notification> findByMemberIdAndIsReadFalse(Long memberId, Pageable pageable);

    long countByMemberIdAndIsReadFalse(Long memberId);

    Optional<Notification> findByIdAndMemberId(Long id, Long memberId);

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE Notification n SET n.isRead = true, n.readAt = :readAt "
            + "WHERE n.memberId = :memberId AND n.isRead = false"
    )
    int markAllAsRead(@Param("memberId") Long memberId, @Param("readAt") LocalDateTime readAt);

    List<Notification> findByMemberIdAndIsReadFalse(Long memberId);

    List<Notification> findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
        Long memberId,
        Long lastEventId,
        LocalDateTime after
    );

    boolean existsBySourceEventIdAndMemberIdAndType(String sourceEventId, Long memberId, NotificationType type);

    /**
     * 보관 기간(90일)이 지난 알림을 생성 시각 오름차순으로 지정된 건수만큼 물리 삭제한다 (스케줄러 명세서 7.2).
     *
     * @param cutoff 삭제 기준 시각 ({@code created_at < cutoff})
     * @param limit 1회 삭제 상한 건수
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "DELETE FROM notifications WHERE created_at < :cutoff ORDER BY created_at ASC LIMIT :limit",
        nativeQuery = true
    )
    int deleteExpiredNotifications(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 탈퇴한 회원의 모든 알림 내역을 물리 삭제한다 (이벤트 계약서 4.1 M3 MemberWithdrawn).
     *
     * @param memberId 탈퇴한 회원 ID
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.memberId = :memberId")
    int deleteAllByMemberId(@Param("memberId") Long memberId);
}
