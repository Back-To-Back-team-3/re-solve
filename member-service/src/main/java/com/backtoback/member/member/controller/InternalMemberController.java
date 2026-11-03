package com.backtoback.member.member.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.member.member.dto.InternalMemberStatusResponse;
import com.backtoback.member.member.service.MemberService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/internal/v1/members/{memberId}")
@RequiredArgsConstructor
public class InternalMemberController {

    private final MemberService memberService;

    /**
     * 1.7.1 회원 상태 조회 (내부) — 다른 서비스가 {@code SUSPENDED} 차단을 확인할 때 쓴다. 없는 회원은 404 {@code MEMBER_NOT_FOUND}.
     */
    @GetMapping("/status")
    public ResponseEntity<InternalMemberStatusResponse> getStatus(@PathVariable Long memberId) {
        return ResponseEntity.ok(InternalMemberStatusResponse.from(memberService.getMember(memberId)));
    }
}
