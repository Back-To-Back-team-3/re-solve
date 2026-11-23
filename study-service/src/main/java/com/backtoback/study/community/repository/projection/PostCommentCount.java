package com.backtoback.study.community.repository.projection;

/**
 * 게시글별 댓글 수 집계 결과 (API 명세서 5.5.1 {@code commentCount}).
 */
public interface PostCommentCount {

    Long getPostId();

    long getCommentCount();
}
