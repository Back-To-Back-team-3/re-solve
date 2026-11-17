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
        value = "INSERT INTO member_replicas " + "(member_id, nickname, profile_image_url, member_status, "
            + "source_version, synced_at, created_at, updated_at) "
            + "VALUES (:memberId, '', NULL, 'WITHDRAWN', :sourceVersion, :syncedAt, NOW(6), NOW(6)) "
            + "ON DUPLICATE KEY UPDATE " + "member_status = 'WITHDRAWN', source_version = :sourceVersion, "
            + "synced_at = :syncedAt, updated_at = NOW(6)",
        nativeQuery = true
    )
    void upsertWithdrawn(
        @Param("memberId") Long memberId,
        @Param("sourceVersion") long sourceVersion,
        @Param("syncedAt") LocalDateTime syncedAt
    );

    /**
     * 회원 정보 변경 이벤트를 복제본에 반영한다. 행이 없으면 삽입하고, 있으면 수신 순서 값이 더 크고
     * 탈퇴 상태가 아닐 때만 갱신한다(event-contract.md §M1, §1.5). 비교와 갱신을 한 문장으로 처리해
     * 같은 회원 이벤트를 동시에 소비해도 순서가 역전되지 않는다(event-contract.md §5.1).
     */
    // MySQL은 ON DUPLICATE KEY UPDATE 대입을 왼쪽부터 적용하고 뒤 조건이 바뀐 값을 읽으므로 source_version을 마지막에 대입한다.
    // 변경이 없어도 드라이버가 1을 돌려줄 수 있어(useAffectedRows=false) 반영 여부 판정에 반환값을 쓰지 않는다.
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO member_replicas
                (member_id, nickname, profile_image_url, member_status, source_version, synced_at,
                created_at, updated_at)
            VALUES (:memberId, :nickname, :profileImageUrl, :memberStatus, :sourceVersion, :syncedAt, NOW(6), NOW(6))
            AS new
            ON DUPLICATE KEY UPDATE
                nickname = IF(new.source_version > member_replicas.source_version
                    AND member_replicas.member_status <> 'WITHDRAWN', new.nickname, member_replicas.nickname),
                profile_image_url = IF(new.source_version > member_replicas.source_version
                    AND member_replicas.member_status <> 'WITHDRAWN', new.profile_image_url,
                    member_replicas.profile_image_url),
                member_status = IF(new.source_version > member_replicas.source_version
                    AND member_replicas.member_status <> 'WITHDRAWN', new.member_status, member_replicas.member_status),
                synced_at = IF(new.source_version > member_replicas.source_version
                    AND member_replicas.member_status <> 'WITHDRAWN', new.synced_at, member_replicas.synced_at),
                updated_at = IF(new.source_version > member_replicas.source_version
                    AND member_replicas.member_status <> 'WITHDRAWN', NOW(6), member_replicas.updated_at),
                source_version = IF(new.source_version > member_replicas.source_version
                    AND member_replicas.member_status <> 'WITHDRAWN', new.source_version,
                    member_replicas.source_version)
            """,
        nativeQuery = true
    )
    void upsertFromMemberUpdated(
        @Param("memberId") Long memberId,
        @Param("nickname") String nickname,
        @Param("profileImageUrl") String profileImageUrl,
        @Param("memberStatus") String memberStatus,
        @Param("sourceVersion") long sourceVersion,
        @Param("syncedAt") LocalDateTime syncedAt
    );

    /**
     * 회원 정지·해제 이벤트의 상태를 복제본에 반영한다. 수신 순서 값이 더 크고 탈퇴 상태가 아닐 때만 갱신한다
     * (event-contract.md §M2, §1.5). 상태 이벤트에는 닉네임이 없어 행을 새로 만들지 않는다(ERD 3.5 nickname NOT NULL).
     *
     * @return 갱신한 행 수. 0이면 복제본이 없거나 지연·탈퇴 회원 이벤트다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            UPDATE member_replicas
            SET member_status = :memberStatus, source_version = :sourceVersion, synced_at = :syncedAt,
                updated_at = NOW(6)
            WHERE member_id = :memberId AND source_version < :sourceVersion AND member_status <> 'WITHDRAWN'
            """,
        nativeQuery = true
    )
    int updateStatusFromMemberSuspended(
        @Param("memberId") Long memberId,
        @Param("memberStatus") String memberStatus,
        @Param("sourceVersion") long sourceVersion,
        @Param("syncedAt") LocalDateTime syncedAt
    );
}
