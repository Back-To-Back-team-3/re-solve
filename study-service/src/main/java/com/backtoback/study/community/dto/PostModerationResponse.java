package com.backtoback.study.community.dto;

import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;

/**
 * 운영진 게시글 관리 결과 (API 명세서 5.5.5).
 */
public record PostModerationResponse(String postId, boolean isPinned, boolean isNotice, PostStatus status) {

    public static PostModerationResponse from(Post post) {
        return new PostModerationResponse(
            String.valueOf(post.getId()),
            post.isPinned(),
            post.isNotice(),
            post.getStatus()
        );
    }
}
