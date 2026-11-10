package com.backtoback.judge.judging.store;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.domain.SubmissionDraft;

import lombok.RequiredArgsConstructor;

/** 공통 소비 트랜잭션 안에서 외부 접수 ID를 기준으로 제출과 최초 작업을 멱등 저장한다. */
@Repository
@RequiredArgsConstructor
public class ExternalSubmissionAcceptanceStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 외부 제출과 최초 채점 작업을 공통 소비기의 트랜잭션에서 저장한다.
     * 런타임과 테스트 메타데이터는 원격 자료를 조회한 뒤 실행기가 고정한다.
     *
     * @param draft 문맥에 맞게 변환한 원래 접수
     * @throws IllegalArgumentException 제출이 유효하지 않은 경우
     * @throws com.backtoback.judge.global.error.JudgeException {@code UNSUPPORTED_LANGUAGE}: 지원하지 않는 언어인 경우
     * @throws org.springframework.dao.DataAccessException 제출·작업 저장이 실패한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void accept(SubmissionDraft draft) {
        var submission = Submission.queued(draft);
        Assert.isTrue(submission.getContext() != Submission.Context.PRACTICE, "외부 접수 문맥이 필요합니다.");
        var now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        String context = submission.getContext().name();
        try {
            jdbc
                .update(
                    """
                        INSERT INTO submissions(member_id,problem_id,problem_revision_id,context,context_id,
                            source_submission_id,language,source_code,code_size_bytes,status,judge_attempt,
                            submitted_at,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,'QUEUED',1,?,?,?)
                        """,
                    draft.memberId(),
                    draft.problemId(),
                    draft.problemRevisionId(),
                    context,
                    draft.contextId(),
                    draft.sourceSubmissionId(),
                    draft.language(),
                    draft.sourceCode(),
                    submission.getCodeSizeBytes(),
                    draft.submittedAt(),
                    now,
                    now
                );
        } catch (DuplicateKeyException duplicate) {
            // 현재 읽기로 이미 커밋된 접수를 확인한다. 기존 작업이나 제출은 갱신하지 않는다.
            if (jdbc.queryForList("""
                SELECT id FROM submissions WHERE context=? AND source_submission_id=? FOR SHARE
                """, Long.class, context, draft.sourceSubmissionId()).isEmpty()) {
                throw duplicate;
            }
            return;
        }
        long submissionId = jdbc.queryForObject("""
            SELECT id FROM submissions WHERE context=? AND source_submission_id=?
            """, Long.class, context, draft.sourceSubmissionId());
        jdbc.update("""
            INSERT INTO judge_jobs(submission_id,judge_attempt,status,try_count,unknown_retry_count,
                next_run_at,deadline_at,created_at,updated_at)
            VALUES (?,1,'PENDING',1,0,?,?,?,?)
            """, submissionId, now, now.plusMinutes(10), now, now);
    }

}
