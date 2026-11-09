package com.backtoback.contest.exam.repository;

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
}
