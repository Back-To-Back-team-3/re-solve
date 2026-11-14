package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.service.ProblemImageUploadTracking.Receipt;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 이미지 접수와 S3 쓰기·검증을 별도 트랜잭션으로 조정한다. */
@Service
@RequiredArgsConstructor
public class ProblemImageUploadService {
    private final ProblemImageUploadTracking tracking;
    private final ProblemImageValidator validator;
    private final ProblemAssetObjectWriter writer;
    private final ProblemAssetObjectReader reader;
    private final Clock clock;

    /** DB 행 잠금을 외부 저장 호출에 걸쳐 유지하지 않는다(API 명세서 2.4.1). */
    @Transactional(propagation = Propagation.NEVER)
    public Result upload(String bucket, Long ownerId, String key, byte[] bytes) {
        String type = validator.validate(bytes);
        ProblemAssetContent content = ProblemAssetContent.fromBytes(bytes);
        Receipt receipt;
        try {
            receipt = tracking.start(ownerId, key, content, type);
        } catch (DataIntegrityViolationException exception) {
            if (!isKeyCollision(exception)) {
                throw exception;
            }
            receipt = tracking.start(ownerId, key, content, type);
        }
        if (receipt.responseStatus() != null) {
            return new Result(receipt.responseStatus(), receipt.responseJson());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!now.isBefore(receipt.expiry())) {
            return new Result(503, tracking.failed(receipt));
        }
        try (Lease lease = new Lease(tracking, receipt, Duration.between(now, receipt.expiry()).dividedBy(2))) {
            String version;
            try {
                version = writer.put(bucket, receipt.objectKey(), content, type);
            } catch (S3Exception exception) {
                lease.stop();
                if (lease.healthy() && exception.statusCode() >= 400 && exception.statusCode() < 500
                    && exception.statusCode() != 408 && exception.statusCode() != 429) {
                    return new Result(503, tracking.failed(lease.receipt()));
                }
                recordUnknown(lease.receipt());
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            } catch (RuntimeException exception) {
                lease.stop();
                recordUnknown(lease.receipt());
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            try {
                reader.verify(bucket, receipt.objectKey(), version, content);
            } catch (RuntimeException exception) {
                lease.stop();
                recordUnknown(lease.receipt());
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            lease.stop();
            if (!lease.healthy()) {
                recordUnknown(lease.receipt());
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            var completion = tracking.complete(lease.receipt(), version);
            return new Result(completion.status(), completion.json());
        }
    }

    private void recordUnknown(Receipt receipt) {
        try {
            tracking.unknown(receipt);
        } catch (RuntimeException ignored) {
            // 갱신 커밋이 불명확하면 기존 종료 미확인 상태를 유지한다.
        }
    }

    private boolean isKeyCollision(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getErrorCode() == 1062
                && violation.getConstraintName() != null
                && violation.getConstraintName().endsWith("uk_problem_images_owner_idempotency_key")) {
                return true;
            }
        }
        return false;
    }

    /** 보관한 최초 HTTP 응답. */
    public record Result(int status, String json) {
    }

    /** 갱신과 종료를 직렬화해 최종 보호 만료값을 전달한다. */
    private static final class Lease implements AutoCloseable {
        private final ProblemImageUploadTracking tracking;
        private final Thread thread;
        private Receipt receipt;
        private boolean stopped;
        private boolean healthy = true;

        private Lease(ProblemImageUploadTracking tracking, Receipt receipt, Duration firstDelay) {
            this.tracking = tracking;
            this.receipt = receipt;
            this.thread = Thread.ofVirtual().name("problem-image-upload-renewal").start(() -> run(firstDelay));
        }

        private void run(Duration delay) {
            try {
                while (true) {
                    Thread.sleep(delay.isZero() ? Duration.ofNanos(1) : delay);
                    synchronized (this) {
                        if (stopped) {
                            return;
                        }
                        try {
                            receipt = tracking.renew(receipt);
                        } catch (RuntimeException exception) {
                            healthy = false;
                            return;
                        }
                    }
                    delay = Duration.ofHours(12);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private synchronized void stop() {
            stopped = true;
            thread.interrupt();
        }

        private synchronized boolean healthy() {
            return healthy;
        }

        private synchronized Receipt receipt() {
            return receipt;
        }

        @Override
        public void close() {
            stop();
        }
    }
}
