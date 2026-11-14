package com.backtoback.judge.judging.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.judge.judging.domain.ExecutionRuntime;

import jakarta.persistence.LockModeType;

/** {@code ExecutionRuntime} 저장과 해시 기반 재사용을 제공한다. */
public interface ExecutionRuntimeRepository extends JpaRepository<ExecutionRuntime, Long> {
    /**
     * 외부 통과 키의 불변 스냅샷을 잠금 없이 복원한다.
     *
     * @param hash 외부 실행 환경 키
     * @return 저장된 실행 환경
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @Query("select runtime from ExecutionRuntime runtime where runtime.snapshotHash = :hash")
    Optional<ExecutionRuntime> findSnapshot(@Param("hash") String hash);

    /**
     * UNIQUE 해시가 없을 때만 스냅샷을 생성한다. 중복이면 기존 속성·생성 시각을 유지한다.
     * 호출자의 트랜잭션에 참여하며 JPA 중복 INSERT 예외로 트랜잭션을 실패시키지 않는다.
     *
     * @param snapshot 검증한 새 스냅샷 후보
     * @return MySQL이 보고한 영향 행 수; 생성 여부 판단에는 사용하지 않음
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO execution_runtimes
                (snapshot_hash, language, judge0_language_id, runtime_name, runtime_version,
                compiler_options, created_at)
            VALUES (:#{#snapshot.snapshotHash}, :#{#snapshot.language}, :#{#snapshot.judge0LanguageId},
                :#{#snapshot.runtimeName}, :#{#snapshot.runtimeVersion},
                :#{#snapshot.compilerOptions}, :#{#snapshot.createdAt})
            ON DUPLICATE KEY UPDATE id = id
            """,
        nativeQuery = true
    )
    int insertIfAbsent(@Param("snapshot") ExecutionRuntime snapshot);

    /**
     * 동시 등록자가 커밋한 행을 현재 읽기로 조회한다.
     * MySQL REPEATABLE READ의 이전 스냅샷 때문에 방금 확정된 행이 누락되지 않게 한다.
     *
     * @param snapshotHash 전체 속성의 해시
     * @return UNIQUE 해시에 해당하는 영속 스냅샷
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<ExecutionRuntime> findBySnapshotHash(String snapshotHash);

    /**
     * 이전 반복 읽기 스냅샷 이후 확정된 실행 환경도 현재 읽기로 조회한다.
     *
     * @param id 실행 환경 ID
     * @return 저장된 불변 실행 환경
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select runtime from ExecutionRuntime runtime where runtime.id = :id")
    Optional<ExecutionRuntime> findCurrentById(@Param("id") long id);

}
