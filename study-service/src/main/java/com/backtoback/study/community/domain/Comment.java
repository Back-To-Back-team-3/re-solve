package com.backtoback.study.community.domain;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 게시글 댓글(ERD 4.5.13 comments). 삭제·숨김은 상태로만 표시하는 소프트 삭제다.
 * {@code postId}는 서비스 내부 FK만 두고 연관관계로 매핑하지 않는다.
 */
@Getter
@Entity
@Table(
    name = "comments",
    indexes = {
        @Index(
            name = "idx_comments_post_created_at",
            columnList = "post_id, created_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "post_id",
        nullable = false
    )
    private Long postId;

    @Column(
        name = "author_id",
        nullable = false
    )
    private Long authorId;

    @Column(
        nullable = false,
        columnDefinition = "TEXT"
    )
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private CommentStatus status;

    private Comment(Long postId, Long authorId, String content) {
        this.postId = postId;
        this.authorId = authorId;
        this.content = content;
        this.status = CommentStatus.VISIBLE;
    }

    public static Comment create(Long postId, Long authorId, String content) {
        return new Comment(postId, authorId, content);
    }
}
