package com.backtoback.contest.exam.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamStatus;

import jakarta.persistence.LockModeType;

/**
 * 시험을 저장하고 조회한다.
 */
public interface ExamRepository extends JpaRepository<Exam, Long> {
    String VISIBLE_EXAMS = """
        from Exam e where e.studyId = :studyId
        and (:status is null or e.status = :status)
        and (:approved = true or exists (select p.id from ExamParticipant p
            where p.exam = e and p.memberId = :memberId
            and p.status <> com.backtoback.contest.exam.domain.ExamParticipantStatus.CANCELED))
        """;

    @Query("select e " + VISIBLE_EXAMS)
    Page<Exam> findVisible(
        @Param("studyId") long studyId,
        @Param("memberId") long memberId,
        @Param("approved") boolean approved,
        @Param("status") ExamStatus status,
        Pageable pageable
    );

    @Query("select count(e) " + VISIBLE_EXAMS)
    long countVisible(
        @Param("studyId") long studyId,
        @Param("memberId") long memberId,
        @Param("approved") boolean approved,
        @Param("status") ExamStatus status
    );

    /** 마지막 ID 이후의 시험을 스터디별로 조회한다. (API 명세서 §4.7.5) */
    List<Exam> findByStudyIdAndIdGreaterThanOrderByIdAsc(Long studyId, Long id, Pageable pageable);

    /** 설정 수정과 시작 전이를 같은 부모 행에서 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select exam from Exam exam where exam.id = :id")
    Optional<Exam> findForUpdate(@Param("id") Long id);

    String LIFECYCLE_DUE = """
        case when e.status = com.backtoback.contest.exam.domain.ExamStatus.SCHEDULED
            then e.startsAt else e.endsAt end
        """;

    /** 전이 시각과 ID를 기준으로 이전 처리 지점 뒤의 시험을 선정한다. */
    @Query(
        "select e.id as id, " + LIFECYCLE_DUE + " as dueAt from Exam e "
            + "where ((e.status = com.backtoback.contest.exam.domain.ExamStatus.SCHEDULED and e.startsAt <= :now) "
            + "or (e.status = com.backtoback.contest.exam.domain.ExamStatus.IN_PROGRESS and e.endsAt < :now)) "
            + "and (:afterDue is null or " + LIFECYCLE_DUE + " > :afterDue " + "or (" + LIFECYCLE_DUE
            + " = :afterDue and e.id > :afterId)) " + "order by dueAt, e.id"
    )
    List<LifecycleCandidate> findLifecycleCandidates(
        @Param("now") LocalDateTime now,
        @Param("afterDue") LocalDateTime afterDue,
        @Param("afterId") long afterId,
        Pageable pageable
    );

    interface LifecycleCandidate {
        long getId();

        LocalDateTime getDueAt();
    }
}
