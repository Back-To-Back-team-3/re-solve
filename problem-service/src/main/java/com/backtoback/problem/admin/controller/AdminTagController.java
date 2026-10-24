package com.backtoback.problem.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.request.TagUpdateRequest;
import com.backtoback.problem.tag.dto.response.TagResponse;
import com.backtoback.problem.tag.service.TagService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** 관리자의 태그 변경 요청을 검증하고 등록·수정·삭제를 서비스에 위임한다. */
@RestController
@RequiredArgsConstructor
public class AdminTagController {
    private final TagService tagService;

    /**
     * API 명세서 2.5.2 태그 생성 — 멱등키를 확인하고 최초 생성 결과를 응답한다.
     *
     * @param user Gateway에서 인증된 관리자
     * @param key 1~100자의 ASCII 문자로 구성된 관리자별 중복 요청 식별 키
     * @param request 등록할 태그 이름과 표시 순서
     * @return 201 상태의 태그 생성 응답
     */
    @PostMapping("/api/v1/admin/problems/tags")
    public ResponseEntity<ApiResponse<TagResponse>> create(
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) @Pattern(regexp = "\\p{ASCII}+") String key,
        @Valid @RequestBody TagCreateRequest request
    ) {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success("태그가 생성되었습니다.", tagService.create(user.userId(), key, request)));
    }

    /**
     * API 명세서 2.5.3 태그 수정 — 이름 또는 표시 순서의 부분 수정 결과를 응답한다.
     */
    @PatchMapping("/api/v1/admin/problems/tags/{tagId}")
    public ResponseEntity<ApiResponse<TagResponse>> update(
        @PathVariable long tagId,
        @Valid @RequestBody TagUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success("태그가 수정되었습니다.", tagService.update(tagId, request)));
    }

    /**
     * API 명세서 2.5.4 태그 삭제 — 미사용 태그 삭제와 후속 순번 조정을 서비스에 위임한다.
     */
    @DeleteMapping("/api/v1/admin/problems/tags/{tagId}")
    public ResponseEntity<Void> delete(@PathVariable long tagId) {
        tagService.delete(tagId);
        return ResponseEntity.noContent().build();
    }
}
