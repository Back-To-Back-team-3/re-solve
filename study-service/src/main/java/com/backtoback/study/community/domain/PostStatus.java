package com.backtoback.study.community.domain;

/**
 * 게시글 상태(ERD 4.5.12 posts.status). {@code DELETED}는 작성자가, {@code HIDDEN}은 운영진이 바꾸며 행은 삭제하지 않는다.
 */
public enum PostStatus {
    VISIBLE, DELETED, HIDDEN
}
