package com.backtoback.study.replica.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.replica.domain.MemberReplica;

public interface MemberReplicaRepository extends JpaRepository<MemberReplica, Long> {

    Optional<MemberReplica> findByMemberId(Long memberId);

    List<MemberReplica> findAllByMemberIdIn(Collection<Long> memberIds);

    /**
     * 회원 탈퇴 시 복제본을 WITHDRAWN 상태로 갱신한다. 행이 없으면 삽입한다.
     * 지연 도착하는 M1·M2 이벤트가 WITHDRAWN을 덮어쓰지 못하도록 source_version도 갱신한다(event-contract.md §1.5).
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "INSERT INTO member_replicas "
            + "(member_id, nickname, profile_image_url, member_status, source_version, synced_at, created_at, updated_at) "
            + "VALUES (:memberId, '', NULL, 'WITHDRAWN', :sourceVersion, :syncedAt, NOW(6), NOW(6)) "
            + "ON DUPLICATE KEY UPDATE "
            + "member_status = 'WITHDRAWN', source_version = :sourceVersion, synced_at = :syncedAt, updated_at = NOW(6)",
        nativeQuery = true
    )
    void upsertWithdrawn(
        @Param("memberId") Long memberId,
        @Param("sourceVersion") long sourceVersion,
        @Param("syncedAt") LocalDateTime syncedAt
    );
}
