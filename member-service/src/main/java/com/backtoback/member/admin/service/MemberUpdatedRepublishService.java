package com.backtoback.member.admin.service;

import org.springframework.stereotype.Service;

import com.backtoback.member.admin.service.MemberUpdatedRepublishWriter.RepublishChunk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴하지 않은 회원 전원의 {@code MemberUpdated}를 다시 발행한다 (API 명세서 1.6.4).
 * 같은 {@code sourceVersion}을 가진 소비자는 무시하므로 반복 실행해도 복제본 값은 바뀌지 않는다(이벤트 계약 §1.5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberUpdatedRepublishService {

    private final MemberUpdatedRepublishWriter writer;

    /**
     * 트랜잭션 없이 청크를 반복한다. 청크마다 커밋해 회원 수만큼 트랜잭션이 커지지 않게 하고,
     * 중간에 실패하면 이미 커밋한 청크는 남기고 다시 호출로 이어서 반영한다.
     *
     * @return 기록한 {@code MemberUpdated} 수
     */
    public long republishAll(Long actorId, String reason, String clientIp) {
        RepublishChunk chunk = writer.publishFirstChunk(actorId, reason, clientIp);
        long publishedCount = chunk.count();
        while (chunk.hasNext()) {
            chunk = writer.publishChunk(chunk.lastMemberId());
            publishedCount += chunk.count();
        }
        log.info("MemberUpdated 재발행 완료: actorId={}, publishedCount={}", actorId, publishedCount);
        return publishedCount;
    }
}
