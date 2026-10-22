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
        "UPDATE Notification n SET n.isRead = true, n.readAt = :readAt WHERE n.memberId = :memberId AND n.isRead = false"
    )
    int markAllAsRead(@Param("memberId") Long memberId, @Param("readAt") LocalDateTime readAt);

    List<Notification> findByMemberIdAndIsReadFalse(Long memberId);

    List<Notification> findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
        Long memberId,
        Long lastEventId,
        LocalDateTime after
    );

    boolean existsBySourceEventIdAndMemberIdAndType(String sourceEventId, Long memberId, NotificationType type);
}
