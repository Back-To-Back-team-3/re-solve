package com.backtoback.notification.reminder.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.notification.reminder.domain.NotificationReminder;
import com.backtoback.notification.reminder.domain.ReminderStatus;

import jakarta.persistence.LockModeType;

/**
 * 시험 시작 임박 예약 알림 JPA 리포지토리 (스케줄러 명세서 7.1).
 */
public interface NotificationReminderRepository extends JpaRepository<NotificationReminder, Long> {

    Optional<NotificationReminder> findByParticipantId(Long participantId);

    /**
     * MySQL {@code REPEATABLE READ} 격리 수준에서 스냅샷 읽기가 아닌 현재 읽기({@code SELECT ... FOR SHARE})로
     * 다른 트랜잭션이 커밋한 최신 시험 스냅샷·취소 표식 행을 조회한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT r FROM NotificationReminder r WHERE r.participantId = :participantId")
    Optional<NotificationReminder> findByParticipantIdForShare(@Param("participantId") Long participantId);

    List<NotificationReminder> findByStatusAndFireAtLessThanEqualAndStartsAtGreaterThanOrderByFireAtAsc(
        ReminderStatus status,
        LocalDateTime fireAtThreshold,
        LocalDateTime startsAtThreshold,
        Pageable pageable
    );

    /**
     * 상위 트랜잭션을 rollback-only로 오염시키지 않도록 유일 키({@code participant_id}) 충돌만 무연산으로 무시하며 예약 행을 삽입한다.
     * {@code INSERT IGNORE}와 달리 {@code NOT NULL} 위반이나 컬럼 길이 초과 같은 데이터 오류는 정상적으로 예외를 발생시킨다.
     *
     * @return 신규 삽입 시 1, 중복 키 존재 시 0
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "INSERT INTO notification_reminders " + "(member_id, exam_id, participant_id, fire_at, starts_at, "
            + "context_revision, source_event_id, status, created_at, updated_at) "
            + "VALUES (:memberId, :examId, :participantId, :fireAt, :startsAt, "
            + ":contextRevision, :sourceEventId, :status, :now, :now) " + "ON DUPLICATE KEY UPDATE id = id",
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

    /**
     * 종결 상태({@code SENT}, {@code CANCELED})가 된 지 7일이 지난 실제 참가자 예약({@code participant_id > 0})을
     * 지정된 건수만큼 물리 삭제한다 (스케줄러 명세서 7.2).
     * 음수 {@code participant_id}를 사용하는 시험 단위 취소·스냅샷 표식 행은 지연 도착 이벤트 방어를 위해 삭제 대상에서 제외한다.
     *
     * @param cutoff 삭제 기준 시각 ({@code updated_at < cutoff})
     * @param limit 1회 삭제 상한 건수
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "DELETE FROM notification_reminders " + "WHERE status IN ('SENT', 'CANCELED') "
            + "AND updated_at < :cutoff " + "AND participant_id > 0 " + "LIMIT :limit",
        nativeQuery = true
    )
    int deleteExpiredCompletedReminders(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * SQS 최대 보관 기간(14일)이 지나 더 이상 지연·역전 이벤트가 도착할 수 없는 음수 표식 행({@code participant_id < 0})을
     * 지정된 건수만큼 물리 삭제하여 표식 행의 무한 적체를 방지한다 (스케줄러 명세서 0.2.3, 7.2).
     *
     * @param cutoff 삭제 기준 시각 ({@code starts_at < cutoff AND updated_at < cutoff})
     * @param limit 1회 삭제 상한 건수
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "DELETE FROM notification_reminders " + "WHERE status = 'CANCELED' " + "AND updated_at < :cutoff "
            + "AND starts_at < :cutoff " + "AND participant_id < 0 " + "LIMIT :limit",
        nativeQuery = true
    )
    int deleteExpiredSentinelReminders(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 탈퇴한 회원의 실제 시험 참가 예약({@code participant_id > 0})을 모두 물리 삭제한다.
     * 음수 {@code participant_id}로 저장된 탈퇴 표식 행은 유지한다.
     *
     * @param memberId 탈퇴한 회원 ID
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM NotificationReminder r WHERE r.memberId = :memberId AND r.participantId > 0")
    int deleteParticipantRemindersByMemberId(@Param("memberId") Long memberId);
}
