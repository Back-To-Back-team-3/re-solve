package com.backtoback.study.recommendation.repository;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.membership.domain.Study;

/**
 * 스터디 추천 조회 전용 저장소. 콜드 스타트 대상 필터와 최근 활동 집계를 한 쿼리로 처리한다.
 */
public interface StudyRecommendationQueryRepository extends Repository<Study, Long> {

    /**
     * 콜드 스타트 추천 대상을 최근 활동 수 → 현재 인원 → 최신 생성 순으로 조회한다
     * (정책 및 상태 §8.2 스터디 추천 > 추천 대상·콜드 스타트).
     * <p>정렬은 쿼리에 고정되어 있으므로 {@code pageable}에는 정렬 조건을 넣지 않는다.
     *
     * @param memberId 요청 회원 ID. 이 회원의 {@code PENDING}·{@code APPROVED} 가입이 있는 스터디는 제외한다.
     * @param since 활동 집계 시작 시각. 이 시각 이후 인정된 완료 기록만 센다.
     */
    // 활동은 문제집 시작 후 푼 완료(ON_TIME·LATE)만 센다. PRE_SOLVED는 문제집 개설 시 일괄 생성되어
    // 실제 최근 활동이 아니고, REVOKED는 인정이 취소된 기록이다. 게시글 기능은 없어 반영하지 않는다.
    @Query(
        value = """
            SELECT s.id AS studyId, s.name AS name, s.member_count AS memberCount,
                   s.capacity AS capacity, s.status AS status, COALESCE(act.cnt, 0) AS activityCount
            FROM studies s
            LEFT JOIN (
                SELECT a.study_id, COUNT(*) AS cnt
                FROM assignments a
                JOIN assignment_completions c ON c.assignment_id = a.id
                WHERE c.status = 'COMPLETED'
                  AND c.completion_type IN ('ON_TIME', 'LATE')
                  AND c.completed_at >= :since
                GROUP BY a.study_id
            ) act ON act.study_id = s.id
            WHERE s.status = 'RECRUITING'
              AND s.member_count < s.capacity
              AND NOT EXISTS (
                  SELECT 1 FROM study_memberships m
                  WHERE m.study_id = s.id AND m.member_id = :memberId AND m.status IN ('PENDING', 'APPROVED')
              )
            ORDER BY activityCount DESC, s.member_count DESC, s.created_at DESC, s.id DESC
            """,
        countQuery = """
            SELECT COUNT(*)
            FROM studies s
            WHERE s.status = 'RECRUITING'
              AND s.member_count < s.capacity
              AND NOT EXISTS (
                  SELECT 1 FROM study_memberships m
                  WHERE m.study_id = s.id AND m.member_id = :memberId AND m.status IN ('PENDING', 'APPROVED')
              )
            """,
        nativeQuery = true
    )
    Page<PopularStudyRow> findPopularStudies(
        @Param("memberId") Long memberId,
        @Param("since") LocalDateTime since,
        Pageable pageable
    );
}
