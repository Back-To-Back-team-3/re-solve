package com.backtoback.study.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.assignment.dto.FollowUpCandidatesResponse;
import com.backtoback.study.assignment.repository.AssignmentCompletionRepository;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository.FollowUpCandidateView;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * 후속 문제집 후보 조회 정상·예외 시나리오를 검증한다(API 명세서 §5.4.4).
 */
@ExtendWith(MockitoExtension.class)
class AssignmentQueryServiceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long ASSIGNMENT_ID = 100L;
    private static final Long MEMBER_ID = 10L;

    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentProblemRepository assignmentProblemRepository;
    @Mock
    private AssignmentMemberStatusRepository memberStatusRepository;
    @Mock
    private AssignmentCompletionRepository completionRepository;
    @Mock
    private MemberReplicaRepository memberReplicaRepository;

    @InjectMocks
    private AssignmentQueryService assignmentQueryService;

    @Test
    @DisplayName("LEADER가 호출하면 미해결 후보 목록을 반환한다")
    void getFollowUpCandidates_leader_returnsCandidates() {
        // given
        StudyMembership membership = mock(StudyMembership.class);
        given(membership.getRole()).willReturn(MembershipRole.LEADER);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.of(membership));
        given(assignmentRepository.findByIdAndStudyId(ASSIGNMENT_ID, STUDY_ID))
            .willReturn(Optional.of(mock(com.backtoback.study.assignment.domain.Assignment.class)));

        FollowUpCandidateView view = mock(FollowUpCandidateView.class);
        given(view.getProblemId()).willReturn(301L);
        given(view.getTitle()).willReturn("이진탐색");
        given(view.getUnsolvedMemberCount()).willReturn(3L);
        given(assignmentProblemRepository.findFollowUpCandidates(ASSIGNMENT_ID)).willReturn(List.of(view));

        // when
        FollowUpCandidatesResponse response
            = assignmentQueryService.getFollowUpCandidates(STUDY_ID, ASSIGNMENT_ID, MEMBER_ID);

        // then
        assertThat(response.candidates()).hasSize(1);
        FollowUpCandidatesResponse.CandidateItem item = response.candidates().get(0);
        assertThat(item.problemId()).isEqualTo("301");
        assertThat(item.title()).isEqualTo("이진탐색");
        assertThat(item.unsolvedMemberCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("MANAGER가 호출하면 미해결 후보 목록을 반환한다")
    void getFollowUpCandidates_manager_returnsCandidates() {
        // given
        StudyMembership membership = mock(StudyMembership.class);
        given(membership.getRole()).willReturn(MembershipRole.MANAGER);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.of(membership));
        given(assignmentRepository.findByIdAndStudyId(ASSIGNMENT_ID, STUDY_ID))
            .willReturn(Optional.of(mock(com.backtoback.study.assignment.domain.Assignment.class)));
        given(assignmentProblemRepository.findFollowUpCandidates(ASSIGNMENT_ID)).willReturn(List.of());

        // when
        FollowUpCandidatesResponse response
            = assignmentQueryService.getFollowUpCandidates(STUDY_ID, ASSIGNMENT_ID, MEMBER_ID);

        // then
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    @DisplayName("MEMBER 역할이면 AUTH_ACCESS_DENIED 예외가 발생한다")
    void getFollowUpCandidates_member_throwsAccessDenied() {
        // given
        StudyMembership membership = mock(StudyMembership.class);
        given(membership.getRole()).willReturn(MembershipRole.MEMBER);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.of(membership));

        // when & then
        assertThatThrownBy(() -> assignmentQueryService.getFollowUpCandidates(STUDY_ID, ASSIGNMENT_ID, MEMBER_ID))
            .isInstanceOf(StudyException.class)
            .satisfies(
                e -> assertThat(((StudyException)e).getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("스터디 미구성원이면 AUTH_ACCESS_DENIED 예외가 발생한다")
    void getFollowUpCandidates_notApproved_throwsAccessDenied() {
        // given
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> assignmentQueryService.getFollowUpCandidates(STUDY_ID, ASSIGNMENT_ID, MEMBER_ID))
            .isInstanceOf(StudyException.class)
            .satisfies(
                e -> assertThat(((StudyException)e).getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("존재하지 않는 assignmentId이면 STUDY_ASSIGNMENT_NOT_FOUND 예외가 발생한다")
    void getFollowUpCandidates_assignmentNotFound_throwsNotFound() {
        // given
        StudyMembership membership = mock(StudyMembership.class);
        given(membership.getRole()).willReturn(MembershipRole.LEADER);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.of(membership));
        given(assignmentRepository.findByIdAndStudyId(ASSIGNMENT_ID, STUDY_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> assignmentQueryService.getFollowUpCandidates(STUDY_ID, ASSIGNMENT_ID, MEMBER_ID))
            .isInstanceOf(StudyException.class)
            .satisfies(
                e -> assertThat(((StudyException)e).getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ASSIGNMENT_NOT_FOUND)
            );
    }
}
