package com.backtoback.notification.reminder.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.notification.reminder.domain.NotificationReminder;
import com.backtoback.notification.reminder.domain.ReminderStatus;

/**
 * 시험 시작 임박 예약 알림 JPA 리포지토리 (스케줄러 명세서 7.1).
 */
public interface NotificationReminderRepository extends JpaRepository<NotificationReminder, Long> {

    Optional<NotificationReminder> findByParticipantId(Long participantId);

    List<NotificationReminder> findByExamIdAndStatus(Long examId, ReminderStatus status);

    List<NotificationReminder> findByStatusAndFireAtLessThanEqualAndStartsAtGreaterThanOrderByFireAtAsc(
        ReminderStatus status,
        LocalDateTime fireAtThreshold,
        LocalDateTime startsAtThreshold,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE NotificationReminder r "
            + "SET r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SENT, r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SCHEDULED "
            + "AND r.startsAt > :now"
    )
    int markAsSentIfScheduled(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE NotificationReminder r "
            + "SET r.status = com.backtoback.notification.reminder.domain.ReminderStatus.CANCELED, r.updatedAt = :now "
            + "WHERE r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SCHEDULED "
            + "AND r.startsAt <= :now"
    )
    int cancelExpiredScheduled(@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE NotificationReminder r "
            + "SET r.status = com.backtoback.notification.reminder.domain.ReminderStatus.CANCELED, r.updatedAt = :now "
            + "WHERE r.examId = :examId "
            + "AND r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SCHEDULED"
    )
    int cancelAllScheduledByExamId(@Param("examId") Long examId, @Param("now") LocalDateTime now);
}
