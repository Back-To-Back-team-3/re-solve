package com.backtoback.member.admin.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.admin.domain.AuditLog;
import com.backtoback.member.admin.repository.AuditLogRepository;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.member.service.MemberEventPublisher;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private static final String ACTION_SUSPEND = "MEMBER_SUSPEND";
    private static final String ACTION_UNSUSPEND = "MEMBER_UNSUSPEND";
    private static final String ACTION_ROLE_CHANGE = "MEMBER_ROLE_CHANGE";

    private final MemberRepository memberRepository;
    private final AuditLogRepository auditLogRepository;
    private final MemberEventPublisher memberEventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 관리자 회원 목록 (API 명세서 1.6.1). {@code keyword}는 닉네임·GitHub 로그인명의 부분 일치로 찾고,
     * 입력한 {@code %}·{@code _}는 와일드카드가 아니라 문자 그대로 찾는다.
     */
    @Transactional(readOnly = true)
    public Page<Member> searchMembers(String keyword, MemberStatus status, MemberRole role, Pageable pageable) {
        String pattern = StringUtils.hasText(keyword) ? escapeLike(keyword.strip()) : null;
        return memberRepository.search(pattern, status, role, pageable);
    }

    /**
     * 회원을 정지하거나 해제한다 (API 명세서 1.6.2). {@code ACTIVE ↔ SUSPENDED}만 허용하고 탈퇴 회원·같은 상태는 400이다.
     * 같은 트랜잭션에서 감사 로그와 {@code MemberSuspended}(이벤트 계약 M2)를 남긴다.
     */
    @Transactional
    public Member changeStatus(Long actorId, Long memberId, MemberStatus target, String reason, String clientIp) {
        Member member = lockMember(memberId);
        MemberStatus before = member.getStatus();
        if (member.isWithdrawn() || target == MemberStatus.WITHDRAWN || target == before) {
            throw new MemberException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (target == MemberStatus.SUSPENDED) {
            member.suspend(now);
        } else {
            member.unsuspend();
        }
        memberEventPublisher.memberSuspended(member, reason, now);
        String action = target == MemberStatus.SUSPENDED ? ACTION_SUSPEND : ACTION_UNSUSPEND;
        audit(
            actorId,
            action,
            memberId,
            Map.of("status", before.name()),
            Map.of("status", target.name()),
            reason,
            clientIp
        );
        return member;
    }

    /**
     * 회원 권한을 바꾼다 (API 명세서 1.6.3). 본인 권한은 바꿀 수 없고, 같은 권한이면 바꾸지 않고 감사 로그도 남기지 않는다.
     * 바뀐 권한은 다음 Access Token 발급부터 반영된다.
     */
    @Transactional
    public Member changeRole(Long actorId, Long memberId, MemberRole target, String reason, String clientIp) {
        if (actorId.equals(memberId)) {
            throw new MemberException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        Member member = lockMember(memberId);
        MemberRole before = member.getRole();
        if (before == target) {
            return member;
        }
        member.changeRole(target);
        audit(
            actorId,
            ACTION_ROLE_CHANGE,
            memberId,
            Map.of("role", before.name()),
            Map.of("role", target.name()),
            reason,
            clientIp
        );
        return member;
    }

    private Member lockMember(Long memberId) {
        return memberRepository
            .findForUpdateById(memberId)
            .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    private void audit(
        Long actorId,
        String action,
        Long memberId,
        Map<String, String> before,
        Map<String, String> after,
        String reason,
        String clientIp
    ) {
        auditLogRepository
            .save(
                AuditLog
                    .ofMember(
                        actorId,
                        action,
                        memberId,
                        objectMapper.writeValueAsString(before),
                        objectMapper.writeValueAsString(after),
                        reason,
                        clientIp
                    )
            );
    }

    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
