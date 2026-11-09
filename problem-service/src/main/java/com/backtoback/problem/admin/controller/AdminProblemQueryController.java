package com.backtoback.problem.admin.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.admin.dto.AdminProblemDetailResponse;
import com.backtoback.problem.admin.dto.AdminProblemSearchRequest;
import com.backtoback.problem.admin.dto.AdminProblemSummaryResponse;
import com.backtoback.problem.admin.dto.AdminRevisionSummaryResponse;
import com.backtoback.problem.admin.service.AdminProblemQueryService;
import com.backtoback.problem.admin.service.AdminProblemQueryStore;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 관리자 목록과 작성자 전용 고정 회차 조회를 제공한다. */
@RestController
@RequiredArgsConstructor
public class AdminProblemQueryController {
    private final AdminProblemQueryStore store;
    private final AdminProblemQueryService service;
    private final ProblemCreateBodyReader identities;

    /**
     * 관리자 전체 회차 목록을 조회한다(API 명세서 §2.3.2).
     *
     * @param filter 검색 조건
     * @param request 반복 정렬 파라미터를 보존할 요청
     * @return 페이지 응답
     */
    @GetMapping("/api/v1/admin/problems")
    public ResponseEntity<ApiResponse<PageResponse<AdminProblemSummaryResponse>>> list(
        @Valid @ModelAttribute AdminProblemSearchRequest filter,
        HttpServletRequest request
    ) {
        var sorts = request.getParameterValues("sort");
        var query
            = new AdminProblemSearchRequest(
                filter.keyword(),
                filter.difficulty(),
                filter.tagIds(),
                filter.scope(),
                filter.visibility(),
                filter.latestOnly(),
                filter.page(),
                filter.size(),
                sorts == null ? null : List.of(sorts)
            );
        return ResponseEntity
            .ok(ApiResponse.success(PageResponse.from(store.list(query).map(AdminProblemSummaryResponse::from))));
    }

    /**
     * 작성자의 최신 회차 원문을 조회한다(API 명세서 §2.3.3).
     *
     * @param problemId 문제
     * @param user 인증 관리자
     * @return 전체 상세
     */
    @GetMapping("/api/v1/admin/problems/{problemId}")
    public ResponseEntity<ApiResponse<AdminProblemDetailResponse>> detail(
        @PathVariable long problemId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        AdminProblemDetailResponse
                            .from(service.read(problemId, null, identities.authorId(user.userId())))
                    )
            );
    }

    /**
     * 지정 회차의 원문을 조회한다(API 명세서 §2.3.5).
     *
     * @param problemId 소유 문제
     * @param revisionId 고정 회차
     * @param user 인증 관리자
     * @return 회차 상세
     */
    @GetMapping("/api/v1/admin/problems/{problemId}/revisions/{revisionId}")
    public ResponseEntity<ApiResponse<AdminProblemDetailResponse>> revision(
        @PathVariable long problemId,
        @PathVariable long revisionId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        AdminProblemDetailResponse
                            .from(service.read(problemId, revisionId, identities.authorId(user.userId())))
                    )
            );
    }

    /**
     * 작성자의 회차 이력을 조회한다(API 명세서 §2.3.6).
     *
     * @param problemId 소유 문제
     * @param user 인증 관리자
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param sort 회차 정렬
     * @return 메타데이터 페이지
     */
    @GetMapping("/api/v1/admin/problems/{problemId}/revisions")
    public ResponseEntity<ApiResponse<PageResponse<AdminRevisionSummaryResponse>>> history(
        @PathVariable long problemId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "revisionNumber,desc") String sort
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        PageResponse
                            .from(
                                store
                                    .history(problemId, identities.authorId(user.userId()), page, size, sort)
                                    .map(AdminRevisionSummaryResponse::from)
                            )
                    )
            );
    }
}
