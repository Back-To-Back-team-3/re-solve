package com.backtoback.study.membership.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.membership.domain.Study;

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
}
