package com.backtoback.study.community.controller;

import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.community.dto.PostCreateRequest;
import com.backtoback.study.community.dto.PostDetailResponse;
import com.backtoback.study.community.dto.PostIdResponse;
import com.backtoback.study.community.dto.PostModerationRequest;
import com.backtoback.study.community.dto.PostModerationResponse;
import com.backtoback.study.community.dto.PostSummaryResponse;
import com.backtoback.study.community.dto.PostUpdateRequest;
import com.backtoback.study.community.service.PostCommandService;
import com.backtoback.study.community.service.PostModerationService;
import com.backtoback.study.community.service.PostQueryService;
import com.backtoback.study.membership.service.MemberStatusGuard;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 스터디 게시글 API (API 명세서 5.5.1~5.5.5). 쓰기 요청의 정지 회원 확인은 member 원격 조회가 있을 수 있어
 * 서비스 트랜잭션보다 먼저 수행한다.
 */
@RestController
@RequestMapping("/api/v1/studies/{studyId}/posts")
@RequiredArgsConstructor
public class PostController {

    private static final int MAX_PAGE_SIZE = 100;

    // API 명세서 5.5.1 "허용 정렬 필드"에 없는 값으로 정렬을 요청하면 저장소 조회가 실패할 수 있어 미리 걸러낸다.
    private static final Set<String> SORT_PROPERTIES = Set.of("createdAt");

    private final MemberStatusGuard memberStatusGuard;
    private final PostCommandService postCommandService;
    private final PostQueryService postQueryService;
    private final PostModerationService postModerationService;

    /**
     * 5.5.1 게시글 목록 — 고정 글을 먼저 반환한다. {@code isNotice=true}일 때만 공지로 거르고, 생략·{@code false}는 전체다.
     * {@code isHidden=true}면 운영진에게 숨긴 글을 돌려준다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PostSummaryResponse>>> getPosts(
        @PathVariable Long studyId,
        @RequestParam(required = false) Boolean isNotice,
        @RequestParam(required = false) Boolean isHidden,
        @PageableDefault(
            size = 20,
            sort = "createdAt",
            direction = Sort.Direction.DESC
        ) Pageable pageable,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Pageable safePageable = clampPageSize(restrictToAllowedSort(pageable));
        var result
            = postQueryService
                .getPosts(
                    studyId,
                    memberId(user),
                    Boolean.TRUE.equals(isNotice),
                    Boolean.TRUE.equals(isHidden),
                    safePageable
                );
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    /**
     * 5.5.2 게시글 작성 — 스터디 최근 활동 시각도 함께 갱신한다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<PostIdResponse>> createPost(
        @PathVariable Long studyId,
        @Valid @RequestBody PostCreateRequest request,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = memberId(user);
        memberStatusGuard.requireNotSuspended(memberId);
        Long postId = postCommandService.write(studyId, memberId, request);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success("게시글이 작성되었습니다.", PostIdResponse.from(postId)));
    }

    /**
     * 5.5.3 게시글 상세 — 공개 댓글을 작성순으로 함께 반환한다.
     */
    @GetMapping("/{postId}")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPost(
        @PathVariable Long studyId,
        @PathVariable Long postId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity.ok(ApiResponse.success(postQueryService.getPost(studyId, postId, memberId(user))));
    }

    /**
     * 5.5.4 게시글 수정 — 작성자 본인만 제목·본문을 바꾼다.
     */
    @PatchMapping("/{postId}")
    public ResponseEntity<ApiResponse<PostIdResponse>> updatePost(
        @PathVariable Long studyId,
        @PathVariable Long postId,
        @Valid @RequestBody PostUpdateRequest request,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = memberId(user);
        memberStatusGuard.requireNotSuspended(memberId);
        Long updated = postCommandService.edit(studyId, postId, memberId, request);
        return ResponseEntity.ok(ApiResponse.success(PostIdResponse.from(updated)));
    }

    /**
     * 5.5.4 게시글 삭제 — 행을 지우지 않고 {@code DELETED}로 바꾼다.
     */
    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(
        @PathVariable Long studyId,
        @PathVariable Long postId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = memberId(user);
        memberStatusGuard.requireNotSuspended(memberId);
        postCommandService.delete(studyId, postId, memberId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 5.5.5 게시글 고정·공지·숨김 (운영진).
     */
    @PatchMapping("/{postId}/moderation")
    public ResponseEntity<ApiResponse<PostModerationResponse>> moderatePost(
        @PathVariable Long studyId,
        @PathVariable Long postId,
        @Valid @RequestBody PostModerationRequest request,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = memberId(user);
        memberStatusGuard.requireNotSuspended(memberId);
        PostModerationResponse response = postModerationService.moderate(studyId, postId, memberId, request);
        return ResponseEntity.ok(ApiResponse.success("게시글 설정이 변경되었습니다.", response));
    }

    private Long memberId(GatewayUser user) {
        return Long.parseLong(user.userId());
    }

    private Pageable clampPageSize(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }

    // 허용되지 않은 정렬 필드는 제거하고, 남는 조건이 없으면 기본 정렬(createdAt desc)로 되돌린다(선례 StudyController).
    private Pageable restrictToAllowedSort(Pageable pageable) {
        Sort allowedSort
            = Sort.by(pageable.getSort().filter(order -> SORT_PROPERTIES.contains(order.getProperty())).toList());
        Sort sort = allowedSort.isUnsorted() ? Sort.by(Sort.Order.desc("createdAt")) : allowedSort;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
