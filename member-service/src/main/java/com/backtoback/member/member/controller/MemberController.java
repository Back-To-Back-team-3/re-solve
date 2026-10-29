package com.backtoback.member.member.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.member.member.dto.MemberResponse;
import com.backtoback.member.member.dto.MemberUpdateRequest;
import com.backtoback.member.member.dto.MemberUpdateResponse;
import com.backtoback.member.member.service.MemberService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/members/me")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    /**
     * 1.2.1 내 정보 조회
     */
    @GetMapping
    public ResponseEntity<ApiResponse<MemberResponse>> getMe(@AuthenticationPrincipal GatewayUser user) {
        return ResponseEntity.ok(ApiResponse.success(MemberResponse.from(memberService.getMember(memberId(user)))));
    }

    /**
     * 1.2.2 내 정보 수정 — 닉네임을 바꾼다.
     */
    @PatchMapping
    public ResponseEntity<ApiResponse<MemberUpdateResponse>> updateMe(
        @AuthenticationPrincipal GatewayUser user,
        @RequestBody MemberUpdateRequest request
    ) {
        MemberUpdateResponse response
            = MemberUpdateResponse.from(memberService.updateNickname(memberId(user), request.nickname()));
        return ResponseEntity.ok(ApiResponse.success("회원 정보가 수정되었습니다.", response));
    }

    // Gateway가 JWT subject(회원 ID 숫자)를 X-User-Id로 전달한다.
    private Long memberId(GatewayUser user) {
        return Long.valueOf(user.userId());
    }
}
