package com.backtoback.study.assignment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyStatus;

public interface AssignmentProblemRepository extends JpaRepository<AssignmentProblem, Long> {

    List<AssignmentProblem> findByAssignmentIdAndStatus(Long assignmentId, AssignmentProblemStatus status);

    /**
     * 회원에게 배정된 문제인지 판정한다 (정책 및 상태 §8.3 문제 접근 제한, API 명세서 5.4.5·5.7.4).
     * 가입은 대상자 행의 {@code membershipId}가 아니라 회원이 그 스터디에 현재 가진 가입으로 확인한다.
     * 재가입해도 대상자 행에는 처음 편입된 가입 ID가 남기 때문이다.
     */
    @Query(
        "SELECT CASE WHEN COUNT(ap) > 0 THEN true ELSE false END "
            + "FROM AssignmentProblem ap, Assignment a, AssignmentMemberStatus ams, StudyMembership sm, Study s "
            + "WHERE ap.problemId = :problemId " + "AND ap.status = :problemStatus " + "AND a.id = ap.assignmentId "
            + "AND a.status IN :assignmentStatuses " + "AND ams.assignmentId = a.id " + "AND ams.memberId = :memberId "
            + "AND sm.studyId = a.studyId " + "AND sm.memberId = :memberId " + "AND sm.status = :membershipStatus "
            + "AND s.id = a.studyId " + "AND s.status <> :excludedStudyStatus"
    )
    boolean existsAssignedProblem(
        @Param("memberId") Long memberId,
        @Param("problemId") Long problemId,
        @Param("problemStatus") AssignmentProblemStatus problemStatus,
        @Param("assignmentStatuses") List<AssignmentStatus> assignmentStatuses,
        @Param("membershipStatus") MembershipStatus membershipStatus,
        @Param("excludedStudyStatus") StudyStatus excludedStudyStatus
    );

    /**
     * 주어진 문제가 ACTIVE 상태로 배정된 OPEN 문제집의 ID 목록을 반환한다(event-contract.md §J1 study 처리).
     */
    @Query(
        "SELECT ap.assignmentId FROM AssignmentProblem ap " + "WHERE ap.problemId = :problemId "
            + "AND ap.status = :apStatus "
            + "AND ap.assignmentId IN (SELECT a.id FROM Assignment a WHERE a.status = :aStatus)"
    )
    List<Long> findOpenAssignmentIdsByProblemId(
        @Param("problemId") Long problemId,
        @Param("apStatus") AssignmentProblemStatus apStatus,
        @Param("aStatus") AssignmentStatus aStatus
    );

    List<AssignmentProblem> findByAssignmentIdOrderByDisplayOrderAsc(Long assignmentId);

    interface ActiveCountView {
        Long getAssignmentId();

        Long getCount();
    }

    /**
     * 문제집별 활성 문제 수를 일괄 조회한다 (API 명세서 §5.4.2 activeProblemCount).
     */
    @Query(
        "SELECT ap.assignmentId AS assignmentId, COUNT(ap) AS count " + "FROM AssignmentProblem ap "
            + "WHERE ap.assignmentId IN :ids AND ap.status = :status " + "GROUP BY ap.assignmentId"
    )
    List<ActiveCountView> countByAssignmentIdInAndStatus(
        @Param("ids") List<Long> ids,
        @Param("status") AssignmentProblemStatus status
    );

    interface FollowUpCandidateView {
        Long getProblemId();

        String getTitle();

        Long getUnsolvedMemberCount();
    }

    /**
     * 미해결 구성원이 있는 ACTIVE 문제를 미해결 인원 내림차순으로 반환한다 (API 명세서 §5.4.4).
     * unsolvedMemberCount = 편입 멤버 수 - 해당 문제를 완료(COMPLETED)한 멤버 수.
     */
    @Query(
        value = "SELECT " + "    ap.problem_id AS problemId, " + "    ap.problem_title AS title, " + "    ( "
            + "        (SELECT COUNT(*) FROM assignment_member_statuses ams "
            + "         WHERE ams.assignment_id = :assignmentId) " + "        - COALESCE(("
            + "            SELECT COUNT(DISTINCT ac.member_id) " + "            FROM assignment_completions ac "
            + "            WHERE ac.assignment_id = :assignmentId " + "              AND ac.problem_id = ap.problem_id "
            + "              AND ac.status = 'COMPLETED'), 0) " + "    ) AS unsolvedMemberCount "
            + "FROM assignment_problems ap " + "WHERE ap.assignment_id = :assignmentId " + "  AND ap.status = 'ACTIVE' "
            + "HAVING unsolvedMemberCount > 0 " + "ORDER BY unsolvedMemberCount DESC",
        nativeQuery = true
    )
    List<FollowUpCandidateView> findFollowUpCandidates(@Param("assignmentId") Long assignmentId);
}
