package com.backtoback.study.community.dto;

/**
 * 게시글 작성·수정 응답 (API 명세서 5.5.2, 5.5.4).
 */
public record PostIdResponse(String postId) {

    public static PostIdResponse from(Long postId) {
        return new PostIdResponse(String.valueOf(postId));
    }
}
