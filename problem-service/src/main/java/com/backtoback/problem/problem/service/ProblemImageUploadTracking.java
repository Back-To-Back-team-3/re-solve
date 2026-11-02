package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemImage;
import com.backtoback.problem.problem.repository.ProblemImageRepository;

import lombok.RequiredArgsConstructor;

/** 이미지의 멱등 접수와 업로드 상태를 짧은 트랜잭션에서 기록한다. */
@Service
@RequiredArgsConstructor
public class ProblemImageUploadTracking {
    private final ProblemImageRepository images;
    private final Clock clock;

    /** 최초 접수 또는 기존 응답 조회를 파일 잠금으로 직렬화한다(API 명세서 2.4.1). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Receipt start(Long ownerId, String key, ProblemAssetContent content, String contentType) {
        var existing
            = images
                .findIdByOwnerAndKey(ownerId, key)
                .flatMap(images::findByIdForUpdate)
                .filter(image -> key.equals(image.getIdempotencyKey()));
        LocalDateTime now = now();
        if (existing.isPresent()) {
            ProblemImage image = existing.get();
            if (now.isBefore(image.getIdempotencyExpiresAt())) {
                if (!image.getSha256().equals(content.sha256())) {
                    throw new ProblemException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                }
                if (image.getResponseStatus() == null) {
                    throw new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_UPLOAD_IN_PROGRESS);
                }
                return new Receipt(
                    image.getId(),
                    image.getObjectKey(),
                    image.getUploadExpiresAt(),
                    image.getResponseStatus(),
                    image.getResponseJson()
                );
            }
            image.releaseExpiredKey(now);
            images.flush();
        }
        ProblemImage image
            = ProblemImage
                .beginUpload(
                    ownerId,
                    "problem-images/" + UUID.randomUUID(),
                    content.sha256(),
                    contentType,
                    content.sizeBytes(),
                    key,
                    now
                );
        images.saveAndFlush(image);
        return new Receipt(image.getId(), image.getObjectKey(), image.getUploadExpiresAt(), null, null);
    }

    /** 진행 작업의 보호 기간을 파일 잠금 아래 갱신한다(정책 및 상태 §4). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Receipt renew(Receipt receipt) {
        ProblemImage image = images.findByIdForUpdate(receipt.id()).orElseThrow();
        image.renewUpload(receipt.expiry(), now());
        return new Receipt(receipt.id(), receipt.objectKey(), image.getUploadExpiresAt(), null, null);
    }

    /** 쓰기 결과를 확인할 수 없을 때 종료 시각을 남기지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unknown(Receipt receipt) {
        images.findByIdForUpdate(receipt.id()).orElseThrow().recordUnknownUpload(receipt.expiry());
    }

    /** 확정된 쓰기 실패와 최초 오류 응답을 함께 기록한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String failed(Receipt receipt) {
        String response = failureResponse();
        images
            .findByIdForUpdate(receipt.id())
            .orElseThrow()
            .failFinishedUpload(receipt.expiry(), now(), "UPLOAD_FAILED", response);
        return response;
    }

    /** 확인된 저장 결과와 최초 성공 응답을 원자적으로 기록한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Completion complete(Receipt receipt, String version) {
        ProblemImage image = images.findByIdForUpdate(receipt.id()).orElseThrow();
        LocalDateTime finished = now();
        if (!finished.isBefore(receipt.expiry())) {
            String response = failureResponse();
            image.failFinishedUpload(receipt.expiry(), finished, "UPLOAD_EXPIRED", response);
            return new Completion(503, response);
        }
        String response = "{\"success\":true,\"message\":null,\"data\":{\"imageId\":\"" + receipt.id() + "\"}}";
        image.completeUpload(receipt.expiry(), finished, version, response);
        return new Completion(201, response);
    }

    private String failureResponse() {
        return "{\"success\":false,\"error\":{\"code\":\"COMMON_DEPENDENCY_UNAVAILABLE\","
            + "\"message\":\"필요한 서비스를 조회할 수 없습니다.\",\"details\":[]}}";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** 최초 접수 정보 또는 보관된 응답. */
    public record Receipt(
        Long id,
        String objectKey,
        LocalDateTime expiry,
        Integer responseStatus,
        String responseJson
    ) {
    }

    /** 최초 종결 상태와 응답. */
    public record Completion(int status, String json) {
    }
}
