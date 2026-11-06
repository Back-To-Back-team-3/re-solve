package com.backtoback.study.assignment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;

public interface AssignmentProblemRepository extends JpaRepository<AssignmentProblem, Long> {

    List<AssignmentProblem> findByAssignmentIdAndStatus(Long assignmentId, AssignmentProblemStatus status);

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
