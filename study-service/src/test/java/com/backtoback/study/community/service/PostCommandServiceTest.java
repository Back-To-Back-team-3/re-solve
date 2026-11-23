package com.backtoback.study.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.exception.ErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;
import com.backtoback.study.community.dto.PostCreateRequest;
import com.backtoback.study.community.dto.PostUpdateRequest;
import com.backtoback.study.community.repository.PostRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyRepository;

@ExtendWith(MockitoExtension.class)
class PostCommandServiceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long MEMBER_ID = 7L;
    private static final Long POST_ID = 88L;
    private static final Long OTHER_MEMBER_ID = 9L;
    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");

    @Mock
    private StudyRepository studyRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private CommunityAccessChecker accessChecker;

    private PostCommandService service;

    @BeforeEach
    void setUp() {
        service
            = new PostCommandService(
                studyRepository,
                postRepository,
                accessChecker,
                Clock.fixed(NOW, ClockConfiguration.SERVICE_ZONE)
            );
    }

    @Test
    @DisplayName("구성원이 일반 글을 작성하면 저장하고 스터디 활동 시각을 고정 시계 시각으로 갱신한다")
    void write_member_savesPostAndRecordsActivity() {
        // given
        Study study = openStudy();
        StudyMembership member = mock(StudyMembership.class);
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(study));
        given(accessChecker.requireApprovedMember(STUDY_ID, MEMBER_ID)).willReturn(member);
        given(postRepository.save(any(Post.class))).willAnswer(invocation -> withId(invocation.getArgument(0)));

        // when
        Long postId = service.write(STUDY_ID, MEMBER_ID, new PostCreateRequest("풀이 공유", "BFS로 풀었습니다.", null));

        // then
        ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(saved.capture());
        assertThat(postId).isEqualTo(POST_ID);
        assertThat(saved.getValue().getStudyId()).isEqualTo(STUDY_ID);
        assertThat(saved.getValue().getAuthorId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getValue().isNotice()).isFalse();
        assertThat(study.getLastActivityAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 10, 0));
    }

    @Test
    @DisplayName("운영진이 공지로 작성하면 공지 글로 저장한다")
    void write_operatorNotice_savesNotice() {
        // given
        StudyMembership leader = mock(StudyMembership.class);
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(openStudy()));
        given(accessChecker.requireApprovedMember(STUDY_ID, MEMBER_ID)).willReturn(leader);
        given(accessChecker.isOperator(leader)).willReturn(true);
        given(postRepository.save(any(Post.class))).willAnswer(invocation -> withId(invocation.getArgument(0)));

        // when
        service.write(STUDY_ID, MEMBER_ID, new PostCreateRequest("이번 주 공지", "BFS 문제를 풉니다.", true));

        // then
        ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(saved.capture());
        assertThat(saved.getValue().isNotice()).isTrue();
    }

    @Test
    @DisplayName("일반 구성원이 공지로 작성하면 AUTH_ACCESS_DENIED로 거절하고 저장하지 않는다")
    void write_memberNotice_throwsAccessDenied() {
        // given
        Study study = openStudy();
        StudyMembership member = mock(StudyMembership.class);
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(study));
        given(accessChecker.requireApprovedMember(STUDY_ID, MEMBER_ID)).willReturn(member);
        given(accessChecker.isOperator(member)).willReturn(false);

        // when & then
        assertError(new PostCreateRequest("공지", "본문", true), CommonErrorCode.AUTH_ACCESS_DENIED);
        verify(postRepository, never()).save(any());
        assertThat(study.getLastActivityAt()).isNull();
    }

    @Test
    @DisplayName("스터디가 없으면 STUDY_NOT_FOUND, 종료됐으면 STUDY_ALREADY_CLOSED, 구성원이 아니면 AUTH_ACCESS_DENIED로 거절한다")
    void write_invalidStudyOrMember_throws() {
        PostCreateRequest request = new PostCreateRequest("제목", "본문", false);

        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.empty());
        assertError(request, StudyErrorCode.STUDY_NOT_FOUND);

        Study closed = openStudy();
        ReflectionTestUtils.setField(closed, "status", StudyStatus.CLOSED);
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(closed));
        assertError(request, StudyErrorCode.STUDY_ALREADY_CLOSED);

        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(openStudy()));
        given(accessChecker.requireApprovedMember(STUDY_ID, MEMBER_ID))
            .willThrow(new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));
        assertError(request, CommonErrorCode.AUTH_ACCESS_DENIED);

        verify(postRepository, never()).save(any());
    }

    @Test
    @DisplayName("작성자가 공개 글을 수정하면 제목과 본문이 바뀐다")
    void edit_author_updatesTitleAndContent() {
        // given
        Post post = existingPost(MEMBER_ID, PostStatus.VISIBLE);
        givenOpenStudyAndMember();
        given(postRepository.findByIdAndStudyIdWithLock(POST_ID, STUDY_ID)).willReturn(Optional.of(post));

        // when
        Long postId = service.edit(STUDY_ID, POST_ID, MEMBER_ID, new PostUpdateRequest("수정한 제목", "수정한 본문"));

        // then
        assertThat(postId).isEqualTo(POST_ID);
        assertThat(post.getTitle()).isEqualTo("수정한 제목");
        assertThat(post.getContent()).isEqualTo("수정한 본문");
    }

    @Test
    @DisplayName("작성자가 삭제하면 행을 지우지 않고 DELETED 상태가 된다")
    void delete_author_marksDeleted() {
        // given
        Post post = existingPost(MEMBER_ID, PostStatus.VISIBLE);
        givenOpenStudyAndMember();
        given(postRepository.findByIdAndStudyIdWithLock(POST_ID, STUDY_ID)).willReturn(Optional.of(post));

        // when
        service.delete(STUDY_ID, POST_ID, MEMBER_ID);

        // then
        assertThat(post.getStatus()).isEqualTo(PostStatus.DELETED);
        verify(postRepository, never()).delete(any());
    }

    @Test
    @DisplayName("작성자가 아니면 수정·삭제를 AUTH_ACCESS_DENIED로 거절하고 글을 바꾸지 않는다")
    void editOrDelete_notAuthor_throwsAccessDenied() {
        // given
        Post post = existingPost(OTHER_MEMBER_ID, PostStatus.VISIBLE);
        givenOpenStudyAndMember();
        given(postRepository.findByIdAndStudyIdWithLock(POST_ID, STUDY_ID)).willReturn(Optional.of(post));

        // when & then
        assertEditAndDeleteError(CommonErrorCode.AUTH_ACCESS_DENIED);
        assertThat(post.getTitle()).isEqualTo("원래 제목");
        assertThat(post.getStatus()).isEqualTo(PostStatus.VISIBLE);
    }

    @Test
    @DisplayName("숨김·삭제 글이거나 다른 스터디 글이면 수정·삭제를 STUDY_POST_NOT_FOUND로 거절한다")
    void editOrDelete_notVisibleOrOtherStudy_throwsPostNotFound() {
        givenOpenStudyAndMember();

        given(postRepository.findByIdAndStudyIdWithLock(POST_ID, STUDY_ID)).willReturn(Optional.empty());
        assertEditAndDeleteError(StudyErrorCode.STUDY_POST_NOT_FOUND);

        for (PostStatus status : new PostStatus[] {
            PostStatus.HIDDEN,
            PostStatus.DELETED
        }) {
            given(postRepository.findByIdAndStudyIdWithLock(POST_ID, STUDY_ID))
                .willReturn(Optional.of(existingPost(MEMBER_ID, status)));
            assertEditAndDeleteError(StudyErrorCode.STUDY_POST_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("스터디가 없으면 STUDY_NOT_FOUND, 종료됐으면 STUDY_ALREADY_CLOSED로 수정·삭제를 거절하고 게시글을 잠그지 않는다")
    void editOrDelete_missingOrClosedStudy_throws() {
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.empty());
        assertEditAndDeleteError(StudyErrorCode.STUDY_NOT_FOUND);

        Study closed = openStudy();
        ReflectionTestUtils.setField(closed, "status", StudyStatus.CLOSED);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(closed));
        assertEditAndDeleteError(StudyErrorCode.STUDY_ALREADY_CLOSED);

        verify(postRepository, never()).findByIdAndStudyIdWithLock(any(), any());
    }

    private void givenOpenStudyAndMember() {
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(openStudy()));
        given(accessChecker.requireApprovedMember(STUDY_ID, MEMBER_ID)).willReturn(mock(StudyMembership.class));
    }

    private void assertEditAndDeleteError(ErrorCode expected) {
        assertThatThrownBy(() -> service.edit(STUDY_ID, POST_ID, MEMBER_ID, new PostUpdateRequest("제목", "본문")))
            .isInstanceOfSatisfying(StudyException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
        assertThatThrownBy(() -> service.delete(STUDY_ID, POST_ID, MEMBER_ID))
            .isInstanceOfSatisfying(StudyException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    private Post existingPost(Long authorId, PostStatus status) {
        Post post = withId(Post.write(STUDY_ID, authorId, "원래 제목", "원래 본문", false));
        ReflectionTestUtils.setField(post, "status", status);
        return post;
    }

    private void assertError(PostCreateRequest request, ErrorCode expected) {
        assertThatThrownBy(() -> service.write(STUDY_ID, MEMBER_ID, request))
            .isInstanceOfSatisfying(StudyException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    private Study openStudy() {
        return Study.recruit("커뮤니티 스터디", null, "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
    }

    private Post withId(Post post) {
        ReflectionTestUtils.setField(post, "id", POST_ID);
        return post;
    }
}
