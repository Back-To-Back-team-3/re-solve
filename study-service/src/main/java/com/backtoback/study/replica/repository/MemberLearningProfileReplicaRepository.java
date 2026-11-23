package com.backtoback.study.replica.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

import jakarta.persistence.LockModeType;

public interface MemberLearningProfileReplicaRepository extends JpaRepository<MemberLearningProfileReplica, Long> {

    List<MemberLearningProfileReplica> findAllByMemberIdIn(Collection<Long> memberIds);

    /**
     * 회원의 학습 프로필 복제본을 {@code FOR UPDATE}로 조회한다. 같은 회원의 추천 재계산을 이 행 잠금으로 직렬화한다
     * (스케줄러 명세 5.4 경합 시나리오 — 회원 단위 교체 트랜잭션, 나중 커밋이 최종).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MemberLearningProfileReplica m WHERE m.memberId = :memberId")
    Optional<MemberLearningProfileReplica> findByMemberIdForUpdate(@Param("memberId") Long memberId);

    /**
     * 학습 프로필 복제본이 있는 회원 ID를 {@code afterMemberId} 다음부터 오름차순으로 {@code limit}개 조회한다.
     * 추천 갱신 스케줄러가 회원 유일 키로 청크를 나눠 순회한다(스케줄러 명세 5.4). 목록 조회이므로 잠그지 않는다(스케줄러 명세 0.1).
     */
    @Query(
        "SELECT m.memberId FROM MemberLearningProfileReplica m WHERE m.memberId > :afterMemberId ORDER BY m.memberId"
    )
    List<Long> findMemberIdsAfter(@Param("afterMemberId") Long afterMemberId, Limit limit);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM MemberLearningProfileReplica m WHERE m.memberId = :memberId")
    void deleteByMemberId(@Param("memberId") Long memberId);

    /**
     * 학습 프로필 변경 이벤트의 전체 스냅샷으로 복제본을 교체한다. 행이 없으면 삽입하고, 있으면 수신한
     * {@code profileVersion}이 저장된 값보다 클 때만 모든 항목을 바꾼다(event-contract.md §M4, §1.5, ERD 4.5.10).
     * 비교와 교체를 한 문장으로 처리해 같은 회원 이벤트를 동시에 소비해도 순서가 역전되지 않는다.
     * JSON 인자는 직렬화한 원문이며 {@code null}은 미입력을 뜻한다.
     */
    // MySQL은 ON DUPLICATE KEY UPDATE 대입을 왼쪽부터 적용하고 뒤 조건이 바뀐 값을 읽으므로 profile_version을 마지막에 대입한다.
    // 변경이 없어도 드라이버가 1을 돌려줄 수 있어(useAffectedRows=false) 반영 여부 판정에 반환값을 쓰지 않는다.
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO member_learning_profile_replicas
                (member_id, goal, tag_levels_json, preferred_languages_json, active_time_slots_json, profile_version,
                synced_at, created_at, updated_at)
            VALUES (:memberId, :goal, :tagLevelsJson, :preferredLanguagesJson, :activeTimeSlotsJson, :profileVersion,
                :syncedAt, NOW(6), NOW(6))
            AS new
            ON DUPLICATE KEY UPDATE
                goal = IF(new.profile_version > member_learning_profile_replicas.profile_version,
                    new.goal, member_learning_profile_replicas.goal),
                tag_levels_json = IF(new.profile_version > member_learning_profile_replicas.profile_version,
                    new.tag_levels_json, member_learning_profile_replicas.tag_levels_json),
                preferred_languages_json = IF(new.profile_version > member_learning_profile_replicas.profile_version,
                    new.preferred_languages_json, member_learning_profile_replicas.preferred_languages_json),
                active_time_slots_json = IF(new.profile_version > member_learning_profile_replicas.profile_version,
                    new.active_time_slots_json, member_learning_profile_replicas.active_time_slots_json),
                synced_at = IF(new.profile_version > member_learning_profile_replicas.profile_version,
                    new.synced_at, member_learning_profile_replicas.synced_at),
                updated_at = IF(new.profile_version > member_learning_profile_replicas.profile_version,
                    NOW(6), member_learning_profile_replicas.updated_at),
                profile_version = IF(new.profile_version > member_learning_profile_replicas.profile_version,
                    new.profile_version, member_learning_profile_replicas.profile_version)
            """,
        nativeQuery = true
    )
    void upsertFromLearningProfileUpdated(
        @Param("memberId") Long memberId,
        @Param("goal") String goal,
        @Param("tagLevelsJson") String tagLevelsJson,
        @Param("preferredLanguagesJson") String preferredLanguagesJson,
        @Param("activeTimeSlotsJson") String activeTimeSlotsJson,
        @Param("profileVersion") long profileVersion,
        @Param("syncedAt") LocalDateTime syncedAt
    );
}
