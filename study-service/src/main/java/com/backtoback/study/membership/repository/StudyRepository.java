package com.backtoback.study.membership.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.membership.domain.Study;

import jakarta.persistence.LockModeType;

public interface StudyRepository extends JpaRepository<Study, Long>, JpaSpecificationExecutor<Study> {

    /**
     * INSTANT 가입 확보: RECRUITING 상태이고 정원이 남은 경우에만 member_count를 1 올린다(API 명세서 §5.2.2 동시성 보장).
     * MySQL SET은 왼쪽부터 평가하므로 IF 조건은 이미 증가한 member_count를 기준으로 한다.
     * 0이면 호출자가 재조회로 STUDY_NOT_RECRUITING / STUDY_FULL 중 적절한 에러를 선택해야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE studies SET member_count = member_count + 1, "
            + "status = IF(member_count >= capacity, 'ACTIVE', status), "
            + "version = version + 1, updated_at = NOW(6) "
            + "WHERE id = :studyId AND status = 'RECRUITING' AND member_count < capacity",
        nativeQuery = true
    )
    int joinInstantIfRecruiting(@Param("studyId") Long studyId);

    /**
     * 승인 정원 확보: CLOSED가 아니고 정원이 남은 경우에만 member_count를 1 올린다(API 명세서 §5.2.4 승인 ③단계).
     * 0이면 재조회 후 status='CLOSED'이면 STUDY_ALREADY_CLOSED, member_count >= capacity이면 STUDY_FULL.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE studies SET member_count = member_count + 1, "
            + "status = IF(member_count >= capacity, 'ACTIVE', status), "
            + "version = version + 1, updated_at = NOW(6) "
            + "WHERE id = :studyId AND status != 'CLOSED' AND member_count < capacity",
        nativeQuery = true
    )
    int approveIfNotFull(@Param("studyId") Long studyId);

    /**
     * 탈퇴 시 member_count를 1 내리고 ACTIVE+is_auto_reopen=true인 경우 RECRUITING으로 복원한다.
     * CLOSED 스터디는 이 메서드를 호출하지 않는다(호출자 책임).
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE studies SET member_count = member_count - 1, "
            + "status = IF(status = 'ACTIVE' AND is_auto_reopen = true, 'RECRUITING', status), "
            + "version = version + 1, updated_at = NOW(6) " + "WHERE id = :studyId",
        nativeQuery = true
    )
    void leaveAndAutoReopen(@Param("studyId") Long studyId);

    /**
     * 스터디 행에 쓰기 잠금(FOR UPDATE)을 취득해 반환한다(scheduler.md §5.0 잠금 순서 ②).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Study s WHERE s.id = :id")
    Optional<Study> findByIdWithLock(@Param("id") Long id);

    /**
     * 설정 수정: version·status·member_count 조건을 원자적으로 검증하고 갱신한다(API 명세서 §5.3.1).
     * {@code capacity} 파라미터는 SET과 WHERE 절에서 모두 쓰이며, WHERE 절의 {@code member_count <= :capacity}는
     * 현재 구성원 수가 새 정원을 초과하는 경우 UPDATE가 발생하지 않게 막는다.
     * 0이면 호출자가 재조회로 STUDY_ALREADY_CLOSED / STUDY_UPDATE_CONFLICT / STUDY_CAPACITY_TOO_SMALL 중
     * 적절한 오류를 선택해야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE studies " + "SET description = :description, join_type = :joinType, "
            + "capacity = :capacity, is_auto_reopen = :isAutoReopen, " + "version = version + 1, updated_at = NOW(6) "
            + "WHERE id = :studyId AND version = :version " + "AND status <> 'CLOSED' AND member_count <= :capacity",
        nativeQuery = true
    )
    int updateSettings(
        @Param("studyId") Long studyId,
        @Param("version") Long version,
        @Param("description") String description,
        @Param("joinType") String joinType,
        @Param("capacity") int capacity,
        @Param("isAutoReopen") boolean isAutoReopen
    );
}
