package com.backtoback.problem.tag.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.problem.tag.domain.TagCreationRecord;

import jakarta.persistence.LockModeType;

public interface TagCreationRecordRepository extends JpaRepository<TagCreationRecord, Long> {
    /**
     * 관리자와 멱등키에 해당하는 요청 기록이 없으면 등록한다.
     * 동일 키가 있으면 기존 기록을 유지한다. 활성 쓰기 트랜잭션에서 호출한다.
     *
     * @param adminId 요청한 관리자 ID
     * @param key 관리자별 중복 요청 식별 키
     * @param requestName 원본 요청의 태그 이름
     * @param requestDisplayOrder 원본 요청의 표시 순서
     * @param expiresAt 등록할 요청의 만료 시각
     * @param now 요청 기록의 생성·수정 시각
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            INSERT INTO tag_creation_records
                (admin_id, idempotency_key, request_name, request_display_order, expires_at,
                    created_at, updated_at, version)
            VALUES (:adminId, :key, :requestName, :requestDisplayOrder, :expiresAt, :now, :now, 0)
            ON DUPLICATE KEY UPDATE id = id
            """,
        nativeQuery = true
    )
    void insertIfAbsent(
        @Param("adminId") String adminId,
        @Param("key") String key,
        @Param("requestName") String requestName,
        @Param("requestDisplayOrder") int requestDisplayOrder,
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("now") LocalDateTime now
    );

    /**
     * 관리자와 멱등키로 요청 기록을 조회하고 쓰기 잠금을 획득한다.
     * 활성 쓰기 트랜잭션에서 호출하며 트랜잭션 종료까지 잠금을 유지한다.
     *
     * @param adminId 요청한 관리자 ID
     * @param key 관리자별 중복 요청 식별 키
     * @return 일치하는 요청 기록; 없으면 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TagCreationRecord r where r.adminId = :adminId and r.idempotencyKey = :key")
    Optional<TagCreationRecord> findForUpdate(@Param("adminId") String adminId, @Param("key") String key);

    /**
     * 만료 기록의 ID 후보를 제한된 수만큼 잠금 없이 조회한다.
     * 삭제 시점의 잠금과 만료 재확인은 별도 건별 트랜잭션에서 수행한다.
     *
     * @param now 만료 여부를 판단할 기준 시각
     * @param batchSize 한 번에 조회할 최대 기록 수
     * @return 만료 시각과 ID 순으로 조회한 삭제 후보 ID 목록
     */
    @Query(
        value = """
            SELECT id FROM tag_creation_records
            WHERE expires_at <= :now ORDER BY expires_at, id LIMIT :batchSize
            """,
        nativeQuery = true
    )
    List<Long> findExpiredIds(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    /**
     * 요청 기록을 조회하고, 잠기지 않은 행의 쓰기 잠금을 획득한다. 잠긴 행은 건너뛴다.
     * READ_COMMITTED 트랜잭션에서 호출한다.
     *
     * @param id 요청 기록 ID
     * @return 행이 없거나 잠겨 있으면 빈 Optional; 행이 있고 잠금을 획득했으면 요청 기록
     */
    @Query(
        value = "SELECT * FROM tag_creation_records WHERE id = :id FOR UPDATE SKIP LOCKED",
        nativeQuery = true
    )
    Optional<TagCreationRecord> findByIdForUpdateSkipLocked(@Param("id") Long id);
}
