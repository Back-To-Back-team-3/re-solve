package com.backtoback.study.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.exception.ErrorCode;
import com.backtoback.study.community.domain.Post;
import com.backtoback.study.community.domain.PostStatus;
import com.backtoback.study.community.dto.PostModerationRequest;
import com.backtoback.study.community.dto.PostModerationResponse;
import com.backtoback.study.community.repository.PostRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyRepository;

@ExtendWith(MockitoExtension.class)
class PostModerationServiceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long POST_ID = 88L;
    private static final Long OPERATOR_ID = 3L;
    private static final PostModerationRequest HIDE = new PostModerationRequest(true, true, true);

    @Mock
    private StudyRepository studyRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private CommunityAccessChecker accessChecker;

    @InjectMocks
    private PostModerationService service;

    @Test
    @DisplayName("운영진이 숨기면 HIDDEN, 숨김을 해제하면 VISIBLE이 되고 고정·공지 값이 반영된다")
    void moderate_operator_appliesPinNoticeAndHidden() {
        // given
        Post post = post(PostStatus.VISIBLE);
        givenOpenStudyAndOperator(true);
        given(postRepository.findByIdAndStudyIdWithLock(POST_ID, STUDY_ID)).willReturn(Optional.of(post));

        // when
        PostModerationResponse hidden = service.moderate(STUDY_ID, POST_ID, OPERATOR_ID, HIDE);
        PostModerationResponse restored
            = service.moderate(STUDY_ID, POST_ID, OPERATOR_ID, new PostModerationRequest(false, true, false));

        // then
        assertThat(hidden).isEqualTo(new PostModerationResponse("88", true, true, PostStatus.HIDDEN));
        assertThat(restored).isEqualTo(new PostModerationResponse("88", false, true, PostStatus.VISIBLE));
    }

    @Test
    @DisplayName("일반 구성원이면 AUTH_ACCESS_DENIED로 거절하고 게시글을 잠그지 않는다")
    void moderate_notOperator_throwsAccessDenied() {
        // given
        givenOpenStudyAndOperator(false);

        // when & then
        assertError(CommonErrorCode.AUTH_ACCESS_DENIED);
        verify(postRepository, never()).findByIdAndStudyIdWithLock(any(), any());
    }

    @Test
    @DisplayName("작성자가 삭제한 글이거나 다른 스터디 글이면 STUDY_POST_NOT_FOUND로 거절한다")
    void moderate_deletedOrOtherStudy_throwsPostNotFound() {
        // given
        givenOpenStudyAndOperator(true);
        Post deleted = post(PostStatus.DELETED);

        // when & then
        given(postRepository.findByIdAndStudyIdWithLock(POST_ID, STUDY_ID)).willReturn(Optional.of(deleted));
        assertError(StudyErrorCode.STUDY_POST_NOT_FOUND);
        assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED);

        given(postRepository.findByIdAndStudyIdWithLock(POST_ID, STUDY_ID)).willReturn(Optional.empty());
        assertError(StudyErrorCode.STUDY_POST_NOT_FOUND);
    }

    @Test
    @DisplayName("스터디가 없으면 STUDY_NOT_FOUND, 종료됐으면 STUDY_ALREADY_CLOSED로 거절한다")
    void moderate_missingOrClosedStudy_throws() {
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.empty());
        assertError(StudyErrorCode.STUDY_NOT_FOUND);

        Study closed = openStudy();
        ReflectionTestUtils.setField(closed, "status", StudyStatus.CLOSED);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(closed));
        assertError(StudyErrorCode.STUDY_ALREADY_CLOSED);

        verify(postRepository, never()).findByIdAndStudyIdWithLock(any(), any());
    }

    private void givenOpenStudyAndOperator(boolean operator) {
        StudyMembership membership = mock(StudyMembership.class);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(openStudy()));
        given(accessChecker.requireApprovedMember(STUDY_ID, OPERATOR_ID)).willReturn(membership);
        given(accessChecker.isOperator(membership)).willReturn(operator);
    }

    private void assertError(ErrorCode expected) {
        assertThatThrownBy(() -> service.moderate(STUDY_ID, POST_ID, OPERATOR_ID, HIDE))
            .isInstanceOfSatisfying(StudyException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    private Study openStudy() {
        return Study.recruit("커뮤니티 스터디", null, "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
    }

    private Post post(PostStatus status) {
        Post post = Post.write(STUDY_ID, 7L, "제목", "본문", false);
        ReflectionTestUtils.setField(post, "id", POST_ID);
        ReflectionTestUtils.setField(post, "status", status);
        return post;
    }
}
