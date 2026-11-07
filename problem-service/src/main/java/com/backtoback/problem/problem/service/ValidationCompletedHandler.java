package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemValidation;
import com.backtoback.problem.problem.domain.ProblemVisibility;
import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** P3 결과와 사용 정보 변경에 따른 P1 v2를 공통 소비 트랜잭션에서 반영한다. */
@Component
@RequiredArgsConstructor
public class ValidationCompletedHandler implements EventHandler {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** {@inheritDoc} */
    @Override
    public String eventType() {
        return "ProblemValidationCompleted";
    }

    /** {@inheritDoc} */
    @Override
    public String schemaVersion() {
        return "v1";
    }

    /** {@inheritDoc} */
    @Override
    public void handle(ReceivedEvent event) {
        if (!event.payload().has("runtimeKey") || !event.payload().has("runnerVersion")) {
            throw new IllegalArgumentException("종결 이벤트의 실행 환경 필드가 누락됐습니다.");
        }
        var result = mapper.treeToValue(event.payload(), Result.class);
        if (!event.metadata().aggregateId().equals(result.validationId())) {
            throw new IllegalArgumentException("검증 식별자 불일치");
        }
        // 최초 일관 읽기 전에 문제 행을 잠가 경쟁 트랜잭션이 확정한 통과 언어까지 읽는다.
        var problem
            = (Problem)entities
                .createNativeQuery("""
                    select p.* from problems p join problem_revisions r on r.problem_id=p.id
                    join problem_validations v on v.problem_revision_id=r.id
                    where v.id=:id and r.id=:revision for update of p
                    """, Problem.class)
                .setParameter("id", Long.parseLong(result.validationId()))
                .setParameter("revision", Long.parseLong(result.problemRevisionId()))
                .getSingleResult();
        var revision
            = entities
                .find(ProblemRevision.class, Long.valueOf(result.problemRevisionId()), LockModeType.PESSIMISTIC_WRITE);
        var validation
            = entities
                .find(ProblemValidation.class, Long.valueOf(result.validationId()), LockModeType.PESSIMISTIC_WRITE);
        if (revision == null || !revision.getProblemId().equals(problem.getId())
            || !validation.getProblemRevisionId().equals(revision.getId())
            || !validation.getLanguage().equals(result.language())
            || !validation.getRequestHash().equals(result.requestHash())) {
            throw new IllegalArgumentException("원래 검증 요청과 일치하지 않습니다.");
        }
        // MySQL REPEATABLE READ에서도 잠금 획득 전에 만들어진 스냅샷을 사용하지 않는다.
        var current = jdbc.queryForList("""
            select r.revision_number from problem_revisions r join problem_validations v on v.problem_revision_id=r.id
            where r.problem_id=? and v.result_status='PASSED' order by r.revision_number desc limit 1 for share of v
            """, Integer.class, problem.getId()).stream().findFirst().orElse(null);
        var languages = new TreeSet<>(jdbc.queryForList("""
            select language from problem_validations where problem_revision_id=? and result_status='PASSED'
            order by language for share
            """, String.class, revision.getId()));
        if (validation.isPending() && "PASSED".equals(result.status()) && languages.contains(result.language())) {
            throw new IllegalArgumentException("회차에 이미 통과한 언어의 실행 환경이 있습니다.");
        }
        boolean applied
            = validation
                .complete(
                    result.status(),
                    result.runtimeKey(),
                    result.runnerVersion(),
                    result
                        .finishedAt()
                        .atZoneSameInstant(ClockConfiguration.SERVICE_ZONE)
                        .toLocalDateTime()
                        .truncatedTo(ChronoUnit.MICROS)
                );
        if (!applied || !validation.hasPassed() || (current != null && current > revision.getRevisionNumber())) {
            return;
        }
        languages.add(result.language());
        problem.advanceExecutionVersion();
        var state
            = new State(
                problem.getId().toString(),
                problem.getRowVersion(),
                problem.getScope(),
                problem.getVisibility(),
                revision.getId().toString(),
                revision.getRevisionNumber(),
                List.copyOf(languages),
                current != null && current.intValue() == revision.getRevisionNumber() ? "LANGUAGE_ADDED" : "REVISION",
                OffsetDateTime.now(clock)
            );
        outbox
            .append("PROBLEM", "ProblemStateChanged", "v2", state.problemId(), event.metadata().correlationId(), state);
    }

    /** Judge가 확정한 검증 결과와 통과 환경이다. */
    private record Result(
        String validationId,
        String problemRevisionId,
        String language,
        String requestHash,
        String status,
        String runtimeKey,
        String runnerVersion,
        OffsetDateTime finishedAt
    ) {
    }
    /** 사용 회차 또는 통과 언어가 변경된 뒤 발행할 문제 상태다. */
    private record State(
        String problemId,
        int sourceVersion,
        ProblemScope scope,
        ProblemVisibility visibility,
        String problemRevisionId,
        int revisionNumber,
        List<String> supportedLanguages,
        String changeType,
        @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx") OffsetDateTime changedAt
    ) {
    }
}
