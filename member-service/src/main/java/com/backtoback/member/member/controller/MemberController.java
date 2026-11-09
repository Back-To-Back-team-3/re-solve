package com.backtoback.member.member.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.member.auth.service.AuthService;
import com.backtoback.member.member.dto.LearningProfileRequest;
import com.backtoback.member.member.dto.LearningProfileResponse;
import com.backtoback.member.member.dto.LearningProfileSaveResponse;
import com.backtoback.member.member.dto.MemberResponse;
import com.backtoback.member.member.dto.MemberUpdateRequest;
import com.backtoback.member.member.dto.MemberUpdateResponse;
import com.backtoback.member.member.service.LearningProfileService;
import com.backtoback.member.member.service.MemberService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/members/me")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final LearningProfileService learningProfileService;
    private final AuthService authService;

    /**
     * 1.2.1 내 정보 조회
     */
    @GetMapping
    public ResponseEntity<ApiResponse<MemberResponse>> getMe(@AuthenticationPrincipal GatewayUser user) {
        Long memberId = memberId(user);
        MemberResponse response
            = MemberResponse.from(memberService.getMember(memberId), learningProfileService.hasProfile(memberId));
        return ResponseEntity.ok(ApiResponse.success(response));
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

    /**
     * 1.3.1 학습 프로필 조회 — 미입력이면 {@code data}가 {@code null}이다.
     */
    @GetMapping("/learning-profile")
    public ResponseEntity<ApiResponse<LearningProfileResponse>> getLearningProfile(
        @AuthenticationPrincipal GatewayUser user
    ) {
        LearningProfileResponse response
            = learningProfileService.getProfile(memberId(user)).map(LearningProfileResponse::from).orElse(null);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 1.3.2 학습 프로필 저장 — 전체를 저장하고 최초 입력이면 만든다.
     */
    @PutMapping("/learning-profile")
    public ResponseEntity<ApiResponse<LearningProfileSaveResponse>> saveLearningProfile(
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody LearningProfileRequest request
    ) {
        long profileVersion
            = learningProfileService
                .save(memberId(user), request.goal(), request.preferredLanguages(), request.activeTimeSlots());
        return ResponseEntity
            .ok(ApiResponse.success("학습 프로필이 저장되었습니다.", LearningProfileSaveResponse.of(profileVersion)));
    }

    /**
     * 1.2.3 회원 탈퇴 — 탈퇴 후 이 요청의 세션 토큰을 폐기한다. 다른 세션의 Refresh Token은 탈퇴 회원 재발급 거절로 막힌다.
     */
    @DeleteMapping
    public ResponseEntity<Void> withdraw(
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader(
            name = HttpHeaders.AUTHORIZATION,
            required = false
        ) String authorization
    ) {
        memberService.withdraw(memberId(user));
        try {
            authService.logout(user.userId(), authorization, null);
        } catch (RuntimeException exception) {
            // 탈퇴는 이미 커밋됐고 Refresh Token 재발급도 거절되므로, 폐기 실패로 탈퇴 응답을 바꾸지 않는다.
            log.warn("Token revocation after withdrawal failed: {}", exception.getClass().getSimpleName());
        }
        return ResponseEntity.noContent().build();
    }

    // Gateway가 JWT subject(회원 ID 숫자)를 X-User-Id로 전달한다.
    private Long memberId(GatewayUser user) {
        return Long.valueOf(user.userId());
    }
}
