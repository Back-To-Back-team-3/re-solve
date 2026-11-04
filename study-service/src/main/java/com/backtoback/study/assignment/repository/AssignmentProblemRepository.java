package com.backtoback.study.assignment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;

public interface AssignmentProblemRepository extends JpaRepository<AssignmentProblem, Long> {

    List<AssignmentProblem> findByAssignmentIdAndStatus(Long assignmentId, AssignmentProblemStatus status);

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
}
