package com.backtoback.study.community.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 운영진 게시글 관리 요청 바디 (API 명세서 5.5.5). 화면이 현재 상태 전체를 보내므로 세 값 모두 필수다.
 */
public record PostModerationRequest(
    @NotNull(message = "상단 고정 여부를 입력해 주세요.") Boolean isPinned,
    @NotNull(message = "공지 여부를 입력해 주세요.") Boolean isNotice,
    @NotNull(message = "숨김 여부를 입력해 주세요.") Boolean isHidden
) {
}
