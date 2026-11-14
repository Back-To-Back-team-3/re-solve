package com.backtoback.member.skill.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.member.skill.dto.TagEntryRequest;
import com.backtoback.member.skill.dto.TagEntryResponse;
import com.backtoback.member.skill.dto.TagLevelListResponse;
import com.backtoback.member.skill.service.TagLevelService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/members/me/tag-levels")
@RequiredArgsConstructor
public class TagLevelController {

    private final TagLevelService tagLevelService;

    /**
     * 1.4.1 태그 레벨 목록
     */
    @GetMapping
    public ResponseEntity<ApiResponse<TagLevelListResponse>> getTagLevels(@AuthenticationPrincipal GatewayUser user) {
        return ResponseEntity
            .ok(ApiResponse.success(TagLevelListResponse.from(tagLevelService.getTagLevels(memberId(user)))));
    }

    /**
     * 1.4.2 태그 진입 (진단 건너뛰기) — {@code LV1}로 시작한다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TagEntryResponse>> enter(
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody TagEntryRequest request
    ) {
        TagEntryResponse response
            = TagEntryResponse.from(tagLevelService.enter(memberId(user), Long.valueOf(request.tagId())));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Lv.1로 시작합니다.", response));
    }

    // Gateway가 JWT subject(회원 ID 숫자)를 X-User-Id로 전달한다.
    private Long memberId(GatewayUser user) {
        return Long.valueOf(user.userId());
    }
}
