package com.backtoback.study.community.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.community.domain.Comment;
import com.backtoback.study.community.domain.CommentStatus;
import com.backtoback.study.community.repository.projection.PostCommentCount;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 목록 한 페이지의 게시글별 댓글 수를 한 번에 센다. 댓글이 없는 게시글은 결과에 없다. */
    @Query("""
        SELECT c.postId AS postId, COUNT(c) AS commentCount
        FROM Comment c
        WHERE c.postId IN :postIds AND c.status = :status
        GROUP BY c.postId
        """)
    List<PostCommentCount> countByPostIdsAndStatus(
        @Param("postIds") Collection<Long> postIds,
        @Param("status") CommentStatus status
    );
}
