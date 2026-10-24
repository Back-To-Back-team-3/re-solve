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

    boolean existsByParticipantId(Long participantId);

    List<NotificationReminder> findByStatusAndFireAtLessThanEqualAndStartsAtGreaterThanOrderByFireAtAsc(
        ReminderStatus status,
        LocalDateTime fireAtThreshold,
        LocalDateTime startsAtThreshold,
        Pageable pageable
    );

    /**
     * 상위 트랜잭션을 rollback-only로 오염시키지 않도록 중복 participant_id 충돌을 무시하며 예약 행을 삽입한다.
     *
     * @return 실제 삽입된 행 수 (1 또는 0)
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "INSERT IGNORE INTO notification_reminders "
            + "(member_id, exam_id, participant_id, fire_at, starts_at, "
            + "context_revision, source_event_id, status, created_at, updated_at) "
            + "VALUES (:memberId, :examId, :participantId, :fireAt, :startsAt, "
            + ":contextRevision, :sourceEventId, :status, :now, :now)",
        nativeQuery = true
    )
    int insertIgnoreReminder(
        @Param("memberId") Long memberId,
        @Param("examId") Long examId,
        @Param("participantId") Long participantId,
        @Param("fireAt") LocalDateTime fireAt,
        @Param("startsAt") LocalDateTime startsAt,
        @Param("contextRevision") int contextRevision,
        @Param("sourceEventId") String sourceEventId,
        @Param("status") String status,
        @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE NotificationReminder r " + "SET r.startsAt = :newStartsAt, r.fireAt = :newFireAt, "
            + "r.contextRevision = :examRevision, r.sourceEventId = :sourceEventId, r.updatedAt = :now "
            + "WHERE r.participantId = :snapshotParticipantId " + "AND r.contextRevision < :examRevision"
    )
    int updateExamSnapshotIfNewer(
        @Param("snapshotParticipantId") Long snapshotParticipantId,
        @Param("examRevision") int examRevision,
        @Param("newStartsAt") LocalDateTime newStartsAt,
        @Param("newFireAt") LocalDateTime newFireAt,
        @Param("sourceEventId") String sourceEventId,
        @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE NotificationReminder r " + "SET r.startsAt = :newStartsAt, r.fireAt = :newFireAt, "
            + "r.contextRevision = :examRevision, r.sourceEventId = :sourceEventId, r.updatedAt = :now "
            + "WHERE r.examId = :examId "
            + "AND r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SCHEDULED "
            + "AND r.contextRevision < :examRevision"
    )
    int rescheduleScheduledByExamId(
        @Param("examId") Long examId,
        @Param("examRevision") int examRevision,
        @Param("newStartsAt") LocalDateTime newStartsAt,
        @Param("newFireAt") LocalDateTime newFireAt,
        @Param("sourceEventId") String sourceEventId,
        @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE NotificationReminder r "
            + "SET r.status = com.backtoback.notification.reminder.domain.ReminderStatus.CANCELED, "
            + "r.contextRevision = :examRevision, r.sourceEventId = :sourceEventId, r.updatedAt = :now "
            + "WHERE r.examId = :examId "
            + "AND r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SCHEDULED "
            + "AND r.contextRevision < :examRevision"
    )
    int cancelScheduledByExamIdAndNewerRevision(
        @Param("examId") Long examId,
        @Param("examRevision") int examRevision,
        @Param("sourceEventId") String sourceEventId,
        @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE NotificationReminder r "
            + "SET r.status = com.backtoback.notification.reminder.domain.ReminderStatus.CANCELED, r.updatedAt = :now "
            + "WHERE r.participantId = :participantId "
            + "AND r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SCHEDULED"
    )
    int cancelScheduledByParticipantId(@Param("participantId") Long participantId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE NotificationReminder r "
            + "SET r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SENT, r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.status = com.backtoback.notification.reminder.domain.ReminderStatus.SCHEDULED "
            + "AND r.fireAt <= :now " + "AND r.startsAt > :now"
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
