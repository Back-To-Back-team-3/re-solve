package com.backtoback.notification.notification.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.notification.notification.domain.NotificationWithdrawnMember;

import jakarta.persistence.LockModeType;

/**
 * 탈퇴 회원 기록({@code notification_withdrawn_members}) 저장소 (ERD 4.7.4).
 */
public interface NotificationWithdrawnMemberRepository extends JpaRepository<NotificationWithdrawnMember, Long> {

    /**
     * 해당 회원이 탈퇴 상태인지 확인한다 (SSE 구독 진입 차단용).
     */
    boolean existsByMemberId(Long memberId);

    /**
     * 단일 회원의 탈퇴 기록을 공유 잠금({@code FOR SHARE})으로 조회하여
     * 동시 실행 중인 {@code MemberWithdrawn} 트랜잭션과의 역전을 방지한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT w FROM NotificationWithdrawnMember w WHERE w.memberId = :memberId")
    Optional<NotificationWithdrawnMember> findByMemberIdForShare(@Param("memberId") Long memberId);

    /**
     * 다수 수신자 목록 중 탈퇴한 회원 ID 목록을 단일 {@code IN ... FOR SHARE} 쿼리로 일괄 조회한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT w.memberId FROM NotificationWithdrawnMember w WHERE w.memberId IN :memberIds")
    List<Long> findMemberIdsByMemberIdInForShare(@Param("memberIds") Collection<Long> memberIds);

    /**
     * {@code MemberWithdrawn} 이벤트 수신 시 탈퇴 회원 기록을 멱등하게 삽입한다.
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            INSERT IGNORE INTO notification_withdrawn_members (
                member_id, source_event_id, source_version, withdrawn_at, created_at
            ) VALUES (
                :memberId, :sourceEventId, :sourceVersion, :withdrawnAt, :createdAt
            )
            """,
        nativeQuery = true
    )
    int insertIgnoreWithdrawnMember(
        @Param("memberId") Long memberId,
        @Param("sourceEventId") String sourceEventId,
        @Param("sourceVersion") int sourceVersion,
        @Param("withdrawnAt") LocalDateTime withdrawnAt,
        @Param("createdAt") LocalDateTime createdAt
    );
}
