package com.backtoback.study.community.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.community.domain.Comment;
import com.backtoback.study.community.domain.CommentStatus;
import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;
import com.backtoback.study.community.dto.PostDetailResponse;
import com.backtoback.study.community.dto.PostSummaryResponse;
import com.backtoback.study.community.repository.CommentRepository;
import com.backtoback.study.community.repository.PostRepository;
import com.backtoback.study.community.repository.projection.PostCommentCount;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 구성원의 게시글 조회를 처리한다(API 명세서 5.5.1, 5.5.3). 숨김·삭제 게시글과 댓글은 상세로 보여 주지 않고,
 * 숨긴 글은 운영진이 숨김을 해제할 수 있도록 목록으로만 운영진에게 보여 준다.
 */
@Service
@RequiredArgsConstructor
public class PostQueryService {

    private final StudyRepository studyRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final MemberReplicaRepository memberReplicaRepository;
    private final CommunityAccessChecker accessChecker;

    /**
     * 게시글을 고정 글 먼저, 이후 요청 정렬로 조회한다. 정지 회원과 종료된 스터디도 조회는 허용한다.
     *
     * @param noticeOnly {@code true}면 공지만, {@code false}면 전체
     * @param hiddenOnly {@code true}면 숨긴 글만(운영진 전용), {@code false}면 공개 글
     * @param pageable 정렬은 {@code createdAt}만 허용된 상태로 받는다
     * @throws StudyException {@code STUDY_NOT_FOUND}, {@code AUTH_ACCESS_DENIED}: 구성원이 아니거나 운영진이 아닌데 숨긴 글 요청
     */
    @Transactional(readOnly = true)
    public Page<PostSummaryResponse> getPosts(
        Long studyId,
        Long memberId,
        boolean noticeOnly,
        boolean hiddenOnly,
        Pageable pageable
    ) {
        if (!studyRepository.existsById(studyId)) {
            throw new StudyException(StudyErrorCode.STUDY_NOT_FOUND);
        }
        StudyMembership membership = accessChecker.requireApprovedMember(studyId, memberId);
        if (hiddenOnly && !accessChecker.isOperator(membership)) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }

        PostStatus status = hiddenOnly ? PostStatus.HIDDEN : PostStatus.VISIBLE;
        Pageable pinnedFirst = withPinnedFirst(pageable);
        Page<Post> posts
            = noticeOnly ? postRepository.findByStudyIdAndStatusAndNotice(studyId, status, true, pinnedFirst)
                : postRepository.findByStudyIdAndStatus(studyId, status, pinnedFirst);
        if (!posts.hasContent()) {
            return new PageImpl<>(List.of(), pinnedFirst, posts.getTotalElements());
        }

        List<Long> postIds = posts.map(Post::getId).toList();
        Map<Long, Long> commentCounts
            = commentRepository
                .countByPostIdsAndStatus(postIds, CommentStatus.VISIBLE)
                .stream()
                .collect(Collectors.toMap(PostCommentCount::getPostId, PostCommentCount::getCommentCount));
        Map<Long, MemberReplica> authors = replicasById(posts.map(Post::getAuthorId).toSet());
        return posts
            .map(
                post -> PostSummaryResponse
                    .from(post, authors.get(post.getAuthorId()), commentCounts.getOrDefault(post.getId(), 0L))
            );
    }

    /**
     * 공개 게시글과 공개 댓글을 작성순으로 조회한다. 숨김·삭제 글은 운영진에게도 없는 글로 응답한다.
     *
     * @throws StudyException {@code STUDY_NOT_FOUND}, {@code AUTH_ACCESS_DENIED},
     *     {@code STUDY_POST_NOT_FOUND}: 다른 스터디 글이거나 공개 상태가 아님
     */
    @Transactional(readOnly = true)
    public PostDetailResponse getPost(Long studyId, Long postId, Long memberId) {
        if (!studyRepository.existsById(studyId)) {
            throw new StudyException(StudyErrorCode.STUDY_NOT_FOUND);
        }
        accessChecker.requireApprovedMember(studyId, memberId);
        Post post
            = postRepository
                .findByIdAndStudyId(postId, studyId)
                .filter(found -> found.getStatus() == PostStatus.VISIBLE)
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_POST_NOT_FOUND));

        List<Comment> comments
            = commentRepository.findByPostIdAndStatusOrderByCreatedAtAscIdAsc(postId, CommentStatus.VISIBLE);
        Set<Long> authorIds = new HashSet<>();
        authorIds.add(post.getAuthorId());
        comments.forEach(comment -> authorIds.add(comment.getAuthorId()));
        return PostDetailResponse.from(post, comments, replicasById(authorIds));
    }

    private Map<Long, MemberReplica> replicasById(Set<Long> memberIds) {
        return memberReplicaRepository
            .findAllByMemberIdIn(memberIds)
            .stream()
            .collect(Collectors.toMap(MemberReplica::getMemberId, Function.identity()));
    }

    // 정렬 파라미터와 관계없이 고정 글을 먼저 두고, 같은 시각의 글은 ID 역순으로 순서를 고정한다(API 명세서 5.5.1).
    private Pageable withPinnedFirst(Pageable pageable) {
        Sort sort = Sort.by(Sort.Order.desc("pinned")).and(pageable.getSort()).and(Sort.by(Sort.Order.desc("id")));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
