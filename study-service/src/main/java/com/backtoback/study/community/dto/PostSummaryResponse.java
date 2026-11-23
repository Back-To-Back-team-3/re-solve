package com.backtoback.study.community.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.backtoback.study.community.domain.Post;
import com.backtoback.study.replica.domain.MemberReplica;

/**
 * 게시글 목록 항목 (API 명세서 5.5.1). 작성자 회원 복제본이 없으면 {@code authorNickname}은 {@code null}이다.
 */
public record PostSummaryResponse(
    String postId,
    String title,
    String authorId,
    String authorNickname,
    boolean isPinned,
    boolean isNotice,
    long commentCount,
    OffsetDateTime createdAt
) {

    // DB의 LocalDateTime은 Asia/Seoul 벽시계 값이라 오프셋만 붙여 ISO 8601(+09:00)로 응답한다(API 명세서 0.1).
    private static final ZoneOffset SEOUL_OFFSET = ZoneOffset.ofHours(9);

    public static PostSummaryResponse from(Post post, MemberReplica author, long commentCount) {
        return new PostSummaryResponse(
            String.valueOf(post.getId()),
            post.getTitle(),
            String.valueOf(post.getAuthorId()),
            author == null ? null : author.getNickname(),
            post.isPinned(),
            post.isNotice(),
            commentCount,
            post.getCreatedAt().atOffset(SEOUL_OFFSET)
        );
    }
}
