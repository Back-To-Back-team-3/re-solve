package com.backtoback.problem.problem.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.problem.dto.request.ProblemSearchRequest;
import com.backtoback.problem.problem.dto.response.ProblemSearchResponse;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemReadStore;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 로그인 회원의 일반 공개 문제 검색을 제공한다. */
@RestController
@RequiredArgsConstructor
public class ProblemSearchController {
    private final ProblemReadStore store;
    private final ProblemCreateBodyReader identities;

    /**
     * 일반 공개 사용 회차를 검색한다(API 명세서 §2.1.1).
     *
     * @param user 인증 회원
     * @param request 검색 조건
     * @return 문제 페이지
     */
    @GetMapping("/api/v1/problems")
    public ResponseEntity<ApiResponse<PageResponse<ProblemSearchResponse>>> search(
        @AuthenticationPrincipal GatewayUser user,
        @Valid @ModelAttribute ProblemSearchRequest request
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        PageResponse
                            .from(
                                store
                                    .search(identities.authorId(user.userId()), request)
                                    .map(ProblemSearchResponse::from)
                            )
                    )
            );
    }
}
