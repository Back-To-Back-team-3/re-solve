package com.backtoback.study.community.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.backtoback.study.community.domain.Comment;
import com.backtoback.study.community.domain.Post;
import com.backtoback.study.replica.domain.MemberReplica;

/**
 * 게시글 상세 응답 (API 명세서 5.5.3). {@code comments}에는 공개 댓글만 작성순으로 담는다.
 */
public record PostDetailResponse(
    String postId,
    String title,
    String content,
    Author author,
    boolean isPinned,
    boolean isNotice,
    List<CommentItem> comments,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    // DB의 LocalDateTime은 Asia/Seoul 벽시계 값이라 오프셋만 붙여 ISO 8601(+09:00)로 응답한다(API 명세서 0.1).
    private static final ZoneOffset SEOUL_OFFSET = ZoneOffset.ofHours(9);

    /**
     * 작성자 표시 정보. 회원 복제본이 없으면 {@code nickname}은 {@code null}, 탈퇴 회원은 복제본의 익명 닉네임이다.
     */
    public record Author(String memberId, String nickname) {

        static Author of(Long memberId, Map<Long, MemberReplica> replicas) {
            MemberReplica replica = replicas.get(memberId);
            return new Author(String.valueOf(memberId), replica == null ? null : replica.getNickname());
        }
    }

    /** 상세에 포함하는 댓글 항목. */
    public record CommentItem(String commentId, String content, Author author, OffsetDateTime createdAt) {
    }

    /**
     * @param replicas 게시글·댓글 작성자 ID로 찾을 회원 복제본
     */
    public static PostDetailResponse from(Post post, List<Comment> comments, Map<Long, MemberReplica> replicas) {
        List<CommentItem> items
            = comments
                .stream()
                .map(
                    comment -> new CommentItem(
                        String.valueOf(comment.getId()),
                        comment.getContent(),
                        Author.of(comment.getAuthorId(), replicas),
                        comment.getCreatedAt().atOffset(SEOUL_OFFSET)
                    )
                )
                .toList();
        return new PostDetailResponse(
            String.valueOf(post.getId()),
            post.getTitle(),
            post.getContent(),
            Author.of(post.getAuthorId(), replicas),
            post.isPinned(),
            post.isNotice(),
            items,
            post.getCreatedAt().atOffset(SEOUL_OFFSET),
            post.getUpdatedAt().atOffset(SEOUL_OFFSET)
        );
    }
}
