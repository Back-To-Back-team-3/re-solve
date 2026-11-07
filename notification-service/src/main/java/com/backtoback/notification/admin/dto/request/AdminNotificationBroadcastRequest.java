package com.backtoback.notification.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminNotificationBroadcastRequest(
    @NotBlank(message = "공지 제목은 필수입니다.")
    @Size(
        max = 200,
        message = "공지 제목은 200자 이하이어야 합니다."
    ) String title,

    @NotBlank(message = "공지 내용은 필수입니다.")
    @Size(
        max = 500,
        message = "공지 내용은 500자 이하이어야 합니다."
    ) String content,

    @Size(
        max = 500,
        message = "링크 URL은 500자 이하이어야 합니다."
    ) String linkUrl
) {
}
