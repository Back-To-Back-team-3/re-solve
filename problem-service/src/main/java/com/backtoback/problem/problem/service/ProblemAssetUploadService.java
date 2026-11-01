package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 업로드 추적과 단일 S3 쓰기·내용 확인을 순서대로 조정한다.
 * 기준 문서: 도메인 및 데이터베이스 v1.0 / §4.2.3 본문 파일: 저장과 종료 기록.
 */
@Service
@RequiredArgsConstructor
public class ProblemAssetUploadService {
    private final ProblemAssetUploadTracking tracking;
    private final ProblemAssetObjectWriter writer;
    private final ProblemAssetObjectReader reader;
    private final Clock clock;

    /**
     * 추적 행 커밋 후 S3에 기록하고 저장 내용 확인 후 완료를 기록한다.
     * 쓰기 결과가 불명확하면 종료 시각을 남기지 않는다.
     *
     * @param bucket 저장할 버킷
     * @param ownerId 작성 관리자
     * @param content 저장할 바이트
     * @param body 본문 여부
     * @return 추적 파일 ID
     * @throws IllegalStateException 보호 기간이 만료됐거나 갱신 또는 저장 확인에 실패한 경우
     */
    @Transactional(propagation = Propagation.NEVER)
    public Long upload(String bucket, Long ownerId, ProblemAssetContent content, boolean body) {
        Objects.requireNonNull(bucket);
        var ticket = tracking.start(ownerId, content, body);
        LocalDateTime started = LocalDateTime.now(clock);
        if (!started.isBefore(ticket.expiry())) {
            tracking.discardBeforeWrite(ticket);
            throw new IllegalStateException("업로드 보호 기간이 만료되었습니다.");
        }
        Duration firstDelay = Duration.between(started, ticket.expiry()).dividedBy(2);
        if (firstDelay.isZero()) {
            firstDelay = Duration.ofNanos(1);
        }
        var lease = new ProblemAssetUploadLease(tracking, ticket, firstDelay);
        String version;
        try {
            version = writer.put(bucket, ticket.key(), content);
            reader.verify(bucket, ticket.key(), version, content);
        } catch (RuntimeException failure) {
            lease.close();
            try {
                tracking.recordUnknown(lease.ticket());
            } catch (RuntimeException trackingFailure) {
                failure.addSuppressed(trackingFailure);
            }
            throw failure;
        } finally {
            lease.close();
        }
        try {
            lease.requireHealthy();
        } catch (RuntimeException failure) {
            try {
                tracking.recordUnknown(lease.ticket());
            } catch (RuntimeException trackingFailure) {
                failure.addSuppressed(trackingFailure);
            }
            throw failure;
        }
        // DB 커밋 결과가 불명일 때 완료 기록을 다시 실패로 덮어쓰지 않는다.
        if (!tracking.complete(lease.ticket(), version)) {
            throw new IllegalStateException("업로드 보호 기간이 만료되었습니다.");
        }
        return ticket.id();
    }
}
