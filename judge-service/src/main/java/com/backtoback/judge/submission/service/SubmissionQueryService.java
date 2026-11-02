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
import com.backtoback.judge.submission.dto.SubmissionListRequest;
import com.backtoback.judge.submission.dto.SubmissionListResponse;
import com.backtoback.judge.submission.dto.SubmissionSourceResponse;
import com.backtoback.judge.submission.repository.SubmissionRepository;

import lombok.RequiredArgsConstructor;

/** 인증 회원의 일반 풀이만 조회하며 타인 제출과 미존재를 같은 404로 처리한다. */
@Service
@RequiredArgsConstructor
public class SubmissionQueryService {
    private final SubmissionRepository submissions;

    /**
     * 본인 일반 제출의 요약 필드만 조회하고 안정적인 정렬로 페이지를 반환한다.
     *
     * @param memberId 인증 회원 ID
     * @param request 검증된 목록 필터와 페이지 조건
     * @return 원본 코드를 제외한 제출 요약 페이지
     */
    @Transactional(readOnly = true)
    public PageResponse<SubmissionListResponse> list(long memberId, SubmissionListRequest request) {
        Specification<Submission> filter = (root, query, builder) -> {
            var predicate
                = builder
                    .and(
                        builder.equal(root.get("memberId"), memberId),
                        builder.equal(root.get("context"), Submission.Context.PRACTICE)
                    );
            if (request.context() != null) {
                predicate = builder.and(predicate, builder.equal(root.get("context"), request.context()));
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
