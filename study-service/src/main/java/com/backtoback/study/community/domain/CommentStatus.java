package com.backtoback.study.community.domain;

/**
 * 댓글 상태(ERD 4.5.13 comments.status). {@code DELETED}는 작성자가, {@code HIDDEN}은 운영진이 바꾸며 행은 삭제하지 않는다.
 */
public enum CommentStatus {
    VISIBLE, DELETED, HIDDEN
}
