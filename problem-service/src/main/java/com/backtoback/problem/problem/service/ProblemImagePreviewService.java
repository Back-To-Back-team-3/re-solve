package com.backtoback.problem.problem.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemAssetStatus;
import com.backtoback.problem.problem.repository.ProblemImageRepository;

import lombok.RequiredArgsConstructor;

/** 관리자 본인이 저장한 이미지의 미리보기를 제공한다. */
@Service
@RequiredArgsConstructor
public class ProblemImagePreviewService {
    private final ProblemImageRepository images;
    private final ProblemAssetObjectReader reader;

    /** 읽기 요청은 업로드 보호나 정리 유예를 연장하지 않는다(API 명세서 2.4.2). */
    @Transactional(propagation = Propagation.NEVER)
    public Preview read(String bucket, Long ownerId, Long imageId) {
        var image
            = images.findById(imageId).orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        if (!image.getOwnerId().equals(ownerId) || image.getUploadedAt() == null || image.getUploadFinishedAt() == null
            || (image.getStatus() != ProblemAssetStatus.PENDING && image.getStatus() != ProblemAssetStatus.LINKED)) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND);
        }
        try {
            byte[] bytes = reader.read(bucket, image.getObjectKey(), image.getObjectVersion());
            ProblemAssetContent content = ProblemAssetContent.fromBytes(bytes);
            if (content.sizeBytes() != image.getSizeBytes() || !content.sha256().equals(image.getSha256())) {
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            return new Preview(image.getContentType(), bytes);
        } catch (RuntimeException exception) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 확인된 MIME과 원본 바이트. */
    public record Preview(String contentType, byte[] bytes) {
    }
}
