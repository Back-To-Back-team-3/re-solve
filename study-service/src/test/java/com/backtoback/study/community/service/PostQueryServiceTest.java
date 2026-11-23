package com.backtoback.study.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.community.domain.CommentStatus;
import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;
import com.backtoback.study.community.dto.PostSummaryResponse;
import com.backtoback.study.community.repository.CommentRepository;
import com.backtoback.study.community.repository.PostRepository;
import com.backtoback.study.community.repository.projection.PostCommentCount;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

@ExtendWith(MockitoExtension.class)
class PostQueryServiceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long MEMBER_ID = 7L;
    private static final Long OTHER_AUTHOR_ID = 9L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 23, 10, 0);
    private static final Pageable LATEST = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt")));

    @Mock
    private StudyRepository studyRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private MemberReplicaRepository memberReplicaRepository;
    @Mock
    private CommunityAccessChecker accessChecker;

    @InjectMocks
    private PostQueryService service;

    @Test
    @DisplayName("게시글별 공개 댓글 수와 작성자 닉네임을 합쳐 고정 글 우선 정렬로 반환하고, 댓글이 없으면 0으로 반환한다")
    void getPosts_combinesCommentCountsAndNicknames() {
        // given
        Post first = post(88L, MEMBER_ID);
        Post second = post(89L, OTHER_AUTHOR_ID);
        given(studyRepository.existsById(STUDY_ID)).willReturn(true);
        given(postRepository.findByStudyIdAndStatus(eq(STUDY_ID), eq(PostStatus.VISIBLE), any(Pageable.class)))
            .willAnswer(invocation -> new PageImpl<>(List.of(first, second), invocation.getArgument(2), 2));
        given(commentRepository.countByPostIdsAndStatus(List.of(88L, 89L), CommentStatus.VISIBLE))
            .willReturn(List.of(commentCount(88L, 2)));
        given(memberReplicaRepository.findAllByMemberIdIn(anyCollection()))
            .willReturn(
                List
                    .of(
                        MemberReplica.sync(MEMBER_ID, "user7", null, MemberStatus.ACTIVE, 1L, CREATED_AT),
                        MemberReplica.sync(OTHER_AUTHOR_ID, "탈퇴한 사용자", null, MemberStatus.WITHDRAWN, 2L, CREATED_AT)
                    )
            );

        // when
        Page<PostSummaryResponse> result = service.getPosts(STUDY_ID, MEMBER_ID, false, LATEST);

        // then
        assertThat(result.getContent())
            .extracting(
                PostSummaryResponse::postId,
                PostSummaryResponse::authorNickname,
                PostSummaryResponse::commentCount
            )
            .containsExactly(tuple("88", "user7", 2L), tuple("89", "탈퇴한 사용자", 0L));
        assertThat(result.getContent().get(0).createdAt()).isEqualTo(OffsetDateTime.parse("2026-11-23T10:00:00+09:00"));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).findByStudyIdAndStatus(eq(STUDY_ID), eq(PostStatus.VISIBLE), pageable.capture());
        assertThat(pageable.getValue().getSort())
            .containsExactly(Sort.Order.desc("pinned"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("공지만 조회하면 공지 조건으로 조회하고, 작성자 회원 복제본이 없으면 닉네임을 null로 반환한다")
    void getPosts_noticeOnlyWithoutReplica_returnsNullNickname() {
        // given
        given(studyRepository.existsById(STUDY_ID)).willReturn(true);
        given(
            postRepository
                .findByStudyIdAndStatusAndNotice(eq(STUDY_ID), eq(PostStatus.VISIBLE), eq(true), any(Pageable.class))
        ).willAnswer(invocation -> new PageImpl<>(List.of(post(88L, MEMBER_ID)), invocation.getArgument(3), 1));
        given(commentRepository.countByPostIdsAndStatus(List.of(88L), CommentStatus.VISIBLE)).willReturn(List.of());
        given(memberReplicaRepository.findAllByMemberIdIn(anyCollection())).willReturn(List.of());

        // when
        Page<PostSummaryResponse> result = service.getPosts(STUDY_ID, MEMBER_ID, true, LATEST);

        // then
        assertThat(result.getContent()).singleElement().satisfies(item -> {
            assertThat(item.authorNickname()).isNull();
            assertThat(item.commentCount()).isZero();
        });
    }

    @Test
    @DisplayName("스터디가 없으면 STUDY_NOT_FOUND, 구성원이 아니면 AUTH_ACCESS_DENIED로 거절하고 게시글을 조회하지 않는다")
    void getPosts_invalidStudyOrMember_throws() {
        given(studyRepository.existsById(STUDY_ID)).willReturn(false);
        assertThatThrownBy(() -> service.getPosts(STUDY_ID, MEMBER_ID, false, LATEST))
            .isInstanceOfSatisfying(
                StudyException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_NOT_FOUND)
            );

        given(studyRepository.existsById(STUDY_ID)).willReturn(true);
        given(accessChecker.requireApprovedMember(STUDY_ID, MEMBER_ID))
            .willThrow(new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));
        assertThatThrownBy(() -> service.getPosts(STUDY_ID, MEMBER_ID, false, LATEST))
            .isInstanceOfSatisfying(
                StudyException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );

        verifyNoInteractions(postRepository, commentRepository, memberReplicaRepository);
    }

    private Post post(Long id, Long authorId) {
        Post post = Post.write(STUDY_ID, authorId, "제목 " + id, "본문", false);
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "createdAt", CREATED_AT);
        return post;
    }

    private PostCommentCount commentCount(Long postId, long count) {
        return new PostCommentCount() {

            @Override
            public Long getPostId() {
                return postId;
            }

            @Override
            public long getCommentCount() {
                return count;
            }
        };
    }
}
