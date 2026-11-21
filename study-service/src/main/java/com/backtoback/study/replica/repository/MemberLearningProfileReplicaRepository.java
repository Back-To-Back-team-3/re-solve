package com.backtoback.study.replica.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

public interface MemberLearningProfileReplicaRepository extends JpaRepository<MemberLearningProfileReplica, Long> {

    List<MemberLearningProfileReplica> findAllByMemberIdIn(Collection<Long> memberIds);

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
