package com.backtoback.study.operation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.operation.dto.StudyUpdateRequest;
import com.backtoback.study.operation.dto.StudyUpdateResponse;
import com.backtoback.study.operation.service.StudyOperationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 스터디 운영 명령 API (API 명세서 §5.3 외부 API — 스터디 운영).
 */
@RestController
@RequestMapping("/api/v1/studies")
@RequiredArgsConstructor
public class StudyOperationController {

    private final StudyOperationService studyOperationService;

    /**
     * §5.3.1 스터디 설정 수정 — {@code LEADER}만 호출할 수 있다. null 필드는 변경하지 않는다.
     */
    @PatchMapping("/{studyId}")
    public ResponseEntity<ApiResponse<StudyUpdateResponse>> updateStudy(
        @PathVariable Long studyId,
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody StudyUpdateRequest request
    ) {
        Long requesterId = Long.parseLong(user.userId());
        return ResponseEntity
            .ok(
                ApiResponse
                    .success("스터디 정보가 수정되었습니다.", studyOperationService.updateStudy(studyId, requesterId, request))
            );
    }
}
