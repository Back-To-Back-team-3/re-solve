package com.backtoback.member.admin.service;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.member.admin.domain.AuditLog;
import com.backtoback.member.admin.repository.AuditLogRepository;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.member.service.MemberEventPublisher;

import lombok.RequiredArgsConstructor;

/**
 * 회원 정보 이벤트 재발행의 청크 1개를 트랜잭션 1개로 기록한다 (API 명세서 1.6.4, 이벤트 계약 M1 관리자 재발행).
 * 회원 행은 바꾸지 않고 현재 {@code profile_version}으로 {@code MemberUpdated}만 Outbox에 남긴다.
 */
@Component
@RequiredArgsConstructor
public class MemberUpdatedRepublishWriter {

    static final int CHUNK_SIZE = 500;
    static final String ACTION_REPUBLISH = "MEMBER_UPDATED_REPUBLISH";

    private final MemberRepository memberRepository;
    private final AuditLogRepository auditLogRepository;
    private final MemberEventPublisher memberEventPublisher;

    /**
     * 실행 1회당 감사 로그 1건을 첫 청크와 같은 트랜잭션에 남긴다(ERD 3.3 감사 대상 작업과 같은 트랜잭션).
     */
    @Transactional
    public RepublishChunk publishFirstChunk(Long actorId, String reason, String clientIp) {
        auditLogRepository.save(AuditLog.ofAllMembers(actorId, ACTION_REPUBLISH, reason, clientIp));
        return publish(0L);
    }

    @Transactional
    public RepublishChunk publishChunk(long afterId) {
        return publish(afterId);
    }

    private RepublishChunk publish(long afterId) {
        List<Member> members
            = memberRepository.findRepublishTargets(afterId, MemberStatus.WITHDRAWN, Limit.of(CHUNK_SIZE));
        members.forEach(memberEventPublisher::memberUpdated);
        long lastMemberId = members.isEmpty() ? afterId : members.getLast().getId();
        return new RepublishChunk(members.size(), lastMemberId);
    }

    /**
     * 청크 처리 결과. {@code lastMemberId}는 다음 청크의 커서다.
     */
    public record RepublishChunk(int count, long lastMemberId) {

        boolean hasNext() {
            return count == CHUNK_SIZE;
        }
    }
}
