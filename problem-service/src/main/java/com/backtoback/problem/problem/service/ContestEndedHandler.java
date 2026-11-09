package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.TreeSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemVisibility;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 대회 종료 공개를 소비 기록과 같은 트랜잭션으로 반영한다. */
@Component
@RequiredArgsConstructor
public class ContestEndedHandler implements EventHandler {
    private final ProblemRepository problems;
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** {@inheritDoc} */
    @Override
    public String eventType() {
        return "ContestEnded";
    }

    /** {@inheritDoc} */
    @Override
    public String schemaVersion() {
        return "v1";
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(ReceivedEvent event) {
        var payload = event.payload();
        long contest = positiveString(payload.path("contestId"));
        if (!payload.path("contestRevision").isIntegralNumber() || payload.path("contestRevision").asLong() < 1
            || !payload.path("isProblemsPublicAfterEnd").isBoolean() || !payload.path("problemIds").isArray()
            || !payload.path("endedAt").isString()) {
            throw new IllegalArgumentException("대회 종료 이벤트의 필수 필드가 올바르지 않습니다.");
        }
        OffsetDateTime.parse(payload.path("endedAt").asString());
        var ids = new TreeSet<Long>();
        payload.path("problemIds").forEach(node -> ids.add(positiveString(node)));
        if (!payload.path("isProblemsPublicAfterEnd").asBoolean()) {
            return;
        }
        for (long id : ids) {
            publish(id, contest, event);
        }
    }

    /**
     * 문제를 잠그고 연결과 최신 관리자 상태 작업을 확인한다.
     *
     * @param id 문제 ID
     * @param contest 종료 대회
     * @param event 원래 이벤트
     */
    private void publish(long id, long contest, ReceivedEvent event) {
        var found = problems.findByIdForUpdate(id);
        if (found.isEmpty()) {
            throw new IllegalStateException("대회 종료 문제 연결이 아직 없습니다.");
        }
        var problem = found.get();
        if (problem.getScope() != ProblemScope.EXAM_ONLY) {
            return;
        }
        if (problem.getContestId() == null) {
            throw new IllegalStateException("대회 종료 문제 연결이 아직 없습니다.");
        }
        if (problem.getContestId() != contest || problem.getVisibility() != ProblemVisibility.PRIVATE) {
            return;
        }
        if (jdbc.queryForObject("""
            SELECT COUNT(*) FROM problem_state_operations WHERE problem_id=? AND state='COMMITTED'
                AND result_source_version>?
            """, Integer.class, id, problem.getContestLinkSourceVersion()) > 0) {
            return;
        }
        var revisions = jdbc.query("""
            SELECT r.id,r.revision_number FROM problem_revisions r WHERE r.problem_id=? AND EXISTS
            (SELECT 1 FROM problem_validations v WHERE v.problem_revision_id=r.id
                    AND v.result_status='PASSED')
            ORDER BY r.revision_number DESC LIMIT 1
            """, (rs, index) -> new Revision(rs.getLong(1), rs.getInt(2)), id);
        if (revisions.isEmpty()) {
            throw new IllegalStateException("대회 연결 문제의 통과 회차가 없습니다.");
        }
        var revision = revisions.getFirst();
        var languages = jdbc.queryForList("""
            SELECT DISTINCT language FROM problem_validations WHERE problem_revision_id=?
                AND result_status='PASSED' ORDER BY language
            """, String.class, revision.id());
        var now = LocalDateTime.now(clock);
        int before = problem.getRowVersion();
        problem.publishAfterContest(now);
        var state
            = new State(
                Long.toString(id),
                problem.getRowVersion(),
                "EXAM_ONLY",
                "PUBLIC",
                Long.toString(revision.id()),
                revision.number(),
                languages,
                "VISIBILITY",
                OffsetDateTime.now(clock),
                null
            );
        outbox
            .append("PROBLEM", "ProblemStateChanged", "v2", state.problemId(), event.metadata().correlationId(), state);
        jdbc
            .update(
                """
                    INSERT INTO audit_logs(actor_id,action,target_type,target_id,before_state,after_state,
                        reason,created_at)
                    VALUES(?,'CONTEST_END_PUBLICATION','PROBLEM',?,?,?,?,?)
                    """,
                problem.getContestPolicyActorId(),
                Long.toString(id),
                mapper.writeValueAsString(java.util.Map.of("visibility", "PRIVATE", "rowVersion", before)),
                mapper
                    .writeValueAsString(
                        java.util.Map.of("visibility", "PUBLIC", "rowVersion", problem.getRowVersion())
                    ),
                "ContestEnded contestId=" + contest + " eventId=" + event.metadata().eventId(),
                now
            );
    }

    /**
     * 문자열 식별자만 허용한다.
     *
     * @param node 이벤트 필드
     * @return 양수 식별자
     * @throws IllegalArgumentException 잘못된 필드
     */
    private long positiveString(JsonNode node) {
        if (!node.isString() || !node.asString().matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("대회 종료 식별자가 올바르지 않습니다.");
        }
        return Long.parseLong(node.asString());
    }

    /** 사용 회차다. */
    private record Revision(long id, int number) {
    }
    /** 종료 공개 시 사용 가능한 최신 회차를 알리는 P1 v2다. */
    private record State(
        String problemId,
        int sourceVersion,
        String scope,
        String visibility,
        String problemRevisionId,
        int revisionNumber,
        List<String> supportedLanguages,
        String changeType,
        @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx") OffsetDateTime changedAt,
        String operationId
    ) {
    }
}
