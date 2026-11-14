package com.backtoback.problem.admin.controller;

import java.io.IOException;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.service.ProblemImagePreviewService;
import com.backtoback.problem.problem.service.ProblemImageUploadService;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** 관리자의 이미지 업로드와 미리보기를 처리한다. */
@RestController
@RequiredArgsConstructor
public class AdminProblemImageController {
    private final ProblemImageUploadService uploads;
    private final ProblemImagePreviewService previews;
    private final ProblemImageStorageProperties storage;

    /** API 명세서 2.4.1 본문 이미지 업로드 — 최초 응답을 그대로 반환한다. */
    @PostMapping(
        value = "/api/v1/admin/problems/images",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<String> upload(
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader("Idempotency-Key")
        @Size(
            min = 1,
            max = 100,
            message = "멱등키는 1~100자로 입력해 주세요."
        )
        @Pattern(
            regexp = "\\p{ASCII}+",
            message = "멱등키는 ASCII 문자로 입력해 주세요."
        ) String key,
        @RequestPart("file") MultipartFile file,
        MultipartHttpServletRequest request
    )
        throws IOException {
        if (request.getMultiFileMap().size() != 1 || request.getFiles("file").size() != 1
            || !request.getParameterMap().isEmpty() || file.isEmpty()) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var result = uploads.upload(bucket(), ownerId(user), key, file.getBytes());
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.json());
    }

    /** API 명세서 2.4.2 관리자 이미지 조회 — 본인 파일의 원본을 반환한다. */
    @GetMapping("/api/v1/admin/problems/images/{imageId}/content")
    public ResponseEntity<byte[]> preview(@AuthenticationPrincipal GatewayUser user, @PathVariable long imageId) {
        var preview = previews.read(bucket(), ownerId(user), imageId);
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(preview.contentType()))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(preview.bytes());
    }

    private String bucket() {
        if (storage.bucket() == null || storage.bucket().isBlank()) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        return storage.bucket();
    }

    private Long ownerId(GatewayUser user) {
        try {
            long id = Long.parseLong(user.userId());
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new ProblemException(CommonErrorCode.AUTH_TOKEN_INVALID);
        }
    }
}
