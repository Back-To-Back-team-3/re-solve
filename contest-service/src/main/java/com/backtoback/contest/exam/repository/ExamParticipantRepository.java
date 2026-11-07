package com.backtoback.contest.exam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.contest.exam.domain.ExamParticipant;

import jakarta.persistence.LockModeType;

/** 부모 잠금 뒤 참가 이력을 최신 순으로 잠근다 (ERD 6.4.1). */
public interface ExamParticipantRepository extends JpaRepository<ExamParticipant, Long> {
    /**
     * 부모 시험 잠금 뒤 같은 회원의 참가를 최신 순으로 잠근다.
     *
     * @param examId 부모 시험 식별자
     * @param memberId 참가 회원 식별자
     * @return 잠긴 참가 행 목록
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ExamParticipant p where p.exam.id = :exam and p.memberId = :member order by p.id desc")
    List<ExamParticipant> findForUpdate(@Param("exam") long examId, @Param("member") long memberId);
}
