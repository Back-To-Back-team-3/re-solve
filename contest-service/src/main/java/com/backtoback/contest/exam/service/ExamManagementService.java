package com.backtoback.contest.exam.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.ExamDependencyClient;
import com.backtoback.contest.exam.client.ExamDependencyClient.ProblemSummary;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamProblem;
import com.backtoback.contest.exam.dto.ExamCreateRequest;
import com.backtoback.contest.exam.dto.ExamResponse;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 시험 설정을 검증하고 가드 잠금 아래 선택 회차를 저장한다 (API 명세서 §4.1.1, §4.8). */
@Service
@RequiredArgsConstructor
public class ExamManagementService {
    private final ExamDependencyClient dependencies;
    private final MemberReplicaService members;
    private final ContestResourceGuardRepository guards;
    private final ProblemSnapshotService snapshots;
    private final ExamRepository exams;
    private final ExamProblemRepository examProblems;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** 원격 조회 후 저장 트랜잭션에서 가드와 서버 시각을 다시 확인한다. */
    public ExamResponse create(String studyId, String memberId, ExamCreateRequest request) {
        long study = positiveId(studyId);
        long member = positiveId(memberId);
        var selection = validate(request);
        if (!"ACTIVE".equals(members.refresh(member))) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var studySnapshot = dependencies.study(study);
        if (!Set.of("ACTIVE", "RECRUITING", "CLOSED").contains(studySnapshot.studyStatus())) {
            throw dependencyFailure();
        }
        if ("CLOSED".equals(studySnapshot.studyStatus())) {
            throw new ContestException(ContestErrorCode.EXAM_STUDY_CLOSED);
        }
        if (studySnapshot
            .members()
            .stream()
            .noneMatch(
                item -> Long.toString(member).equals(item.memberId())
                    && ("LEADER".equals(item.role()) || "MANAGER".equals(item.role()))
            )) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var summaries = dependencies.problems(selection.ids()).items();
        Map<Long, ProblemSummary> byId = validateSummaries(selection.ids(), summaries);
        return transactions.execute(status -> save(study, member, request, selection, byId));
    }

    /** 입력의 범위와 중복을 확인한다. */
    private Selection validate(ExamCreateRequest request) {
        if (request == null || request.title() == null || request.title().isBlank() || request.title().length() > 200
            || !"FIXED".equals(request.mode()) || request.startsAt() == null || request.endsAt() == null
            || request.problems() == null || request.problems().isEmpty() || request.problems().size() > 20) {
            throw invalid();
        }
        validateTime(request.startsAt(), request.endsAt());
        var ids = new ArrayList<Long>();
        var orders = new HashSet<Integer>();
        var total = BigDecimal.ZERO;
        for (var problem : request.problems()) {
            if (problem == null || problem.score() == null
                || !problem.score().matches("(?:0|[1-9][0-9]{0,3})\\.[0-9]{2}") || problem.displayOrder() == null
                || problem.displayOrder() < 1 || problem.displayOrder() > request.problems().size()
                || !orders.add(problem.displayOrder())) {
                throw invalid();
            }
            long id = positiveId(problem.problemId());
            if (ids.contains(id)) {
                throw invalid();
            }
            ids.add(id);
            var score = new BigDecimal(problem.score());
            if (score.signum() <= 0) {
                throw invalid();
            }
            total = total.add(score);
        }
        if (total.compareTo(new BigDecimal("9999.99")) > 0) {
            throw invalid();
        }
        return new Selection(List.copyOf(ids), total);
    }

    /** 시작과 길이는 실제 저장 직전에도 현재 서버 시각으로 검사한다. */
    private void validateTime(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        Duration duration = Duration.between(startsAt.toInstant(), endsAt.toInstant());
        if (!startsAt.toInstant().isAfter(clock.instant()) || duration.compareTo(Duration.ofMinutes(1)) < 0
            || duration.compareTo(Duration.ofMinutes(180)) > 0) {
            throw invalid();
        }
    }

    /** 누락·비가용 문제는 거절하고 손상된 응답은 의존 장애로 처리한다. */
    private Map<Long, ProblemSummary> validateSummaries(List<Long> ids, List<ProblemSummary> summaries) {
        var found = new HashMap<Long, ProblemSummary>();
        for (var item : summaries) {
            long id;
            try {
                id = Long.parseLong(item.problemId());
            } catch (RuntimeException failure) {
                throw dependencyFailure();
            }
            if (!ids.contains(id) || found.put(id, item) != null) {
                throw dependencyFailure();
            }
        }
        for (long id : ids) {
            var item = found.get(id);
            if (item == null || !Boolean.TRUE.equals(item.isAvailable())) {
                throw new ContestException(ContestErrorCode.EXAM_PROBLEM_NOT_AVAILABLE);
            }
            if (!"GENERAL".equals(item.scope()) || !"PUBLIC".equals(item.visibility()) || item.sourceVersion() == null
                || item.sourceVersion() <= 0 || item.revisionNumber() == null || item.revisionNumber() <= 0
                || item.title() == null || item.title().isBlank() || item.title().length() > 200
                || item.supportedLanguages() == null || item.supportedLanguages().isEmpty()) {
                throw dependencyFailure();
            }
            if (item
                .supportedLanguages()
                .stream()
                .anyMatch(language -> language == null || !Set.of("java", "python3", "cpp").contains(language))
                || item.supportedLanguages().stream().distinct().count() != item.supportedLanguages().size()) {
                throw dependencyFailure();
            }
            try {
                if (Long.parseLong(item.problemRevisionId()) <= 0) {
                    throw dependencyFailure();
                }
            } catch (NumberFormatException failure) {
                throw dependencyFailure();
            }
        }
        return found;
    }

    /** 가드를 먼저 잠근 뒤 최신 원천 버전과 열린 상태를 확인하고 시험을 저장한다. */
    private ExamResponse save(
        long study,
        long member,
        ExamCreateRequest request,
        Selection selection,
        Map<Long, ProblemSummary> summaries
    ) {
        var resources = new ArrayList<Resource>();
        resources.add(new Resource("STUDY", study));
        selection.ids().forEach(id -> resources.add(new Resource("PROBLEM", id)));
        var locked = guards.lockAll(resources);
        if (locked
            .stream()
            .anyMatch(guard -> "STUDY".equals(guard.resource().type()) && !"OPEN".equals(guard.state()))) {
            throw new ContestException(ContestErrorCode.EXAM_STUDY_CLOSED);
        }
        for (var item : summaries.values()) {
            try {
                snapshots
                    .apply(
                        new ProblemSnapshotService.Snapshot(
                            Long.parseLong(item.problemId()),
                            item.sourceVersion(),
                            item.scope(),
                            item.visibility(),
                            Long.parseLong(item.problemRevisionId()),
                            item.revisionNumber(),
                            item.supportedLanguages(),
                            null
                        )
                    );
            } catch (IllegalArgumentException failure) {
                throw dependencyFailure();
            }
            var row
                = jdbc
                    .queryForMap(
                        "SELECT state, source_version, source_revision_id, source_scope, source_status "
                            + "FROM contest_resource_guards WHERE resource_type = 'PROBLEM' AND resource_id = ?",
                        item.problemId()
                    );
            if (!"OPEN".equals(row.get("state")) || !"GENERAL".equals(row.get("source_scope"))
                || !"PUBLIC".equals(row.get("source_status"))) {
                throw new ContestException(ContestErrorCode.EXAM_PROBLEM_NOT_AVAILABLE);
            }
            if (!(row.get("source_version") instanceof Number version)
                || !(row.get("source_revision_id") instanceof Number revision)
                || version.longValue() != item.sourceVersion()
                || revision.longValue() != Long.parseLong(item.problemRevisionId())) {
                throw dependencyFailure();
            }
        }
        validateTime(request.startsAt(), request.endsAt());
        var exam
            = exams
                .saveAndFlush(
                    Exam
                        .create(
                            study,
                            member,
                            request.title(),
                            ExamMode.FIXED,
                            local(request.startsAt()),
                            local(request.endsAt()),
                            null,
                            1,
                            selection.total()
                        )
                );
        var pinned = new ArrayList<ExamProblem>();
        for (var problem : request.problems()) {
            var item = summaries.get(Long.parseLong(problem.problemId()));
            pinned
                .add(
                    ExamProblem
                        .pin(
                            exam,
                            Long.parseLong(item.problemId()),
                            Long.parseLong(item.problemRevisionId()),
                            item.title(),
                            item.revisionNumber(),
                            new BigDecimal(problem.score()),
                            problem.displayOrder()
                        )
                );
        }
        examProblems.saveAll(pinned);
        return ExamResponse.from(exam, pinned);
    }

    private LocalDateTime local(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ClockConfiguration.SERVICE_ZONE);
    }

    private long positiveId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw invalid();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private ContestException invalid() {
        return new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    private ContestException dependencyFailure() {
        return new ContestException(ContestErrorCode.CONTEST_DEPENDENCY_UNAVAILABLE);
    }

    /** 검증된 식별자 목록과 배점 합계다. */
    private record Selection(List<Long> ids, BigDecimal total) {
    }
}
