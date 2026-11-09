package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.dto.request.ContestProblemLinkRequest;
import com.backtoback.problem.problem.repository.ProblemRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 대회 전용 문제와 고정 회차의 최초 연결을 보존한다. */
@Service
@RequiredArgsConstructor
public class ProblemContestService {
    private final ProblemRepository problems;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper mapper;

    /**
     * 같은 연결은 현재 버전과 관계없이 최초 결과를 반환한다.
     *
     * @param problemId 문제 ID
     * @param request C가 검증한 대회·회차·설정 관리자
     * @param clientIp 감사 주소
     * @return 불변 연결 결과
     * @throws ProblemException 입력·문제·회차·상태·버전 오류
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Problem link(long problemId, ContestProblemLinkRequest request, String clientIp) {
        if (problemId <= 0 || request == null || request.rowVersion() < 1 || request.rowVersion() > Integer.MAX_VALUE) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        long contest = id(request.contestId());
        long revision = id(request.problemRevisionId());
        long actor = id(request.actorMemberId());
        var problem
            = problems
                .findByIdForUpdate(problemId)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        if (problem.getContestId() != null) {
            problem.linkContest(contest, revision, actor, (int)request.rowVersion());
            return problem;
        }
        if (jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM problem_revisions WHERE id=? AND problem_id=?",
                Integer.class,
                revision,
                problemId
            ) == 0) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND);
        }
        if (jdbc.queryForObject("""
            SELECT COUNT(*) FROM problem_validations WHERE problem_revision_id=?
                AND result_status='PASSED'
            """, Integer.class, revision) == 0) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
        problem.linkContest(contest, revision, actor, (int)request.rowVersion());
        jdbc
            .update(
                """
                    INSERT INTO audit_logs(actor_id,action,target_type,target_id,after_state,
                        reason,client_ip,created_at)
                    VALUES(?,'PROBLEM_CONTEST_LINK','PROBLEM',?,?,'CONTEST_LINK',?,?)
                    """,
                actor,
                Long.toString(problemId),
                mapper
                    .writeValueAsString(
                        java.util.Map
                            .of(
                                "contestId",
                                contest,
                                "problemRevisionId",
                                revision,
                                "linkedSourceVersion",
                                problem.getContestLinkSourceVersion()
                            )
                    ),
                clientIp,
                LocalDateTime.now(clock)
            );
        return problem;
    }

    /**
     * 양수 식별 문자열을 해석한다.
     *
     * @param value 요청 식별자
     * @return 양수 식별자
     * @throws ProblemException 식별자 형식 오류
     */
    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }
}
