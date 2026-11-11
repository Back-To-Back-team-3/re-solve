package com.backtoback.judge.submission.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.api.PageResponse;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.dto.SubmissionListRequest;
import com.backtoback.judge.submission.dto.SubmissionListResponse;
import com.backtoback.judge.submission.dto.SubmissionSourceResponse;
import com.backtoback.judge.submission.repository.SubmissionRepository;

import lombok.RequiredArgsConstructor;

/** 인증 회원의 제출 목록과 본인 일반 풀이의 원본 코드를 조회한다. */
@Service
@RequiredArgsConstructor
public class SubmissionQueryService {
    private final SubmissionRepository submissions;

    /**
     * 본인 일반·시험·진단 제출을 안정적으로 정렬한다. 판정 검색은 일반 제출로 제한한다.
     *
     * @param memberId 인증 회원 ID
     * @param request 검증된 목록 필터와 페이지 조건
     * @return 원본 코드를 제외한 제출 요약 페이지
     */
    @Transactional(readOnly = true)
    public PageResponse<SubmissionListResponse> list(long memberId, SubmissionListRequest request) {
        Specification<Submission> filter = (root, query, builder) -> {
            var context = root.<Context>get("context");
            // 판정을 가린 제출은 판정별 조회 결과와 페이지 건수에서도 드러나지 않도록 제외한다.
            var visibleContexts
                = request.verdict() == null ? context.in(Context.PRACTICE, Context.EXAM, Context.DIAGNOSIS)
                    : builder.equal(context, Context.PRACTICE);
            var predicate = builder.and(builder.equal(root.get("memberId"), memberId), visibleContexts);
            if (request.context() != null) {
                predicate = builder.and(predicate, builder.equal(context, request.context()));
            }
            if (request.problemId() != null) {
                predicate
                    = builder
                        .and(
                            predicate,
                            builder
                                .equal(
                                    root.get("problemId"),
                                    com.backtoback.judge.submission.dto.SubmissionCreateRequest
                                        .parseId(request.problemId())
                                )
                        );
            }
            if (request.verdict() != null) {
                predicate = builder.and(predicate, builder.equal(root.get("verdict"), request.verdict()));
            }
            return predicate;
        };
        var direction = request.sort().endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        var page = PageRequest.of(request.page(), request.size(), Sort.by(direction, "submittedAt", "id"));
        return PageResponse
            .from(
                submissions
                    .findBy(filter, query -> query.as(SubmissionSummary.class).page(page))
                    .map(SubmissionListResponse::from)
            );
    }

    /**
     * 본인 일반 제출의 원본 코드를 조회한다.
     *
     * @param memberId 인증 회원 식별자
     * @param submissionId 조회할 제출 식별자
     * @return 언어와 원본 코드
     * @throws JudgeException 타인 제출·다른 문맥·미존재 제출인 경우 SUBMISSION_NOT_FOUND
     */
    @Transactional(readOnly = true)
    public SubmissionSourceResponse source(long memberId, long submissionId) {
        return SubmissionSourceResponse
            .from(
                submissions
                    .findByIdAndMemberIdAndContext(submissionId, memberId, Submission.Context.PRACTICE)
                    .orElseThrow(() -> new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND))
            );
    }
}
