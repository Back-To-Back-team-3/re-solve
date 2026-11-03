package com.backtoback.study.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.assignment.client.ProblemSummariesClient;
import com.backtoback.study.assignment.client.ProblemSummariesResponse;
import com.backtoback.study.assignment.client.ProblemSummaryItem;
import com.backtoback.study.assignment.dto.AssignmentCreateRequest;
import com.backtoback.study.assignment.dto.AssignmentCreateResponse;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;

/**
 * 문제집 생성 정상·예외 시나리오를 검증한다(API 명세서 §5.4.1).
 */
@ExtendWith(MockitoExtension.class)
class AssignmentCommandServiceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long MEMBER_ID = 10L;
    private static final Long PROBLEM_ID = 301L;
    private static final Long ASSIGNMENT_ID = 100L;
    private static final OffsetDateTime STARTS_AT
        = OffsetDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneOffset.of("+09:00"));
    private static final OffsetDateTime DEADLINE_AT
        = OffsetDateTime.of(2026, 11, 17, 23, 59, 59, 0, ZoneOffset.of("+09:00"));

    @Mock
    private StudyRepository studyRepository;
    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private ProblemSummariesClient problemSummariesClient;
    @Mock
    private AssignmentPersistService assignmentPersistService;

    @InjectMocks
    private AssignmentCommandService assignmentCommandService;

    private Study study;
    private StudyMembership leaderMembership;
    private AssignmentCreateRequest request;

    @BeforeEach
    void setUp() {
        study = mock(Study.class);
        given(study.getStatus()).willReturn(StudyStatus.ACTIVE);

        leaderMembership = mock(StudyMembership.class);
        given(leaderMembership.getRole()).willReturn(MembershipRole.LEADER);

        request
            = new AssignmentCreateRequest(
                "1주차 문제집",
                "이진탐색 복습",
                null,
                List.of(PROBLEM_ID),
                false,
                STARTS_AT,
                DEADLINE_AT
            );
    }

    private ProblemSummaryItem availableItem(Long problemId) {
        return new ProblemSummaryItem(
            problemId,
            true,
            1L,
            1,
            "이진탐색",
            1,
            List.of(),
            "GENERAL",
            "PUBLIC",
            "1.0",
            List.of("java")
        );
    }

    private ProblemSummaryItem unavailableItem(Long problemId) {
        return new ProblemSummaryItem(
            problemId,
            false,
            null,
            0,
            "비공개 문제",
            1,
            List.of(),
            "GENERAL",
            "PRIVATE",
            "1.0",
            List.of("java")
        );
    }

    @Test
    @DisplayName("정상 요청이면 assignmentId와 SCHEDULED 상태를 반환한다")
    void createAssignment_success() {
        // given
        ProblemSummariesResponse summaries = new ProblemSummariesResponse(List.of(availableItem(PROBLEM_ID)));

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.of(leaderMembership));
        given(problemSummariesClient.summaries(List.of(PROBLEM_ID))).willReturn(summaries);
        given(assignmentPersistService.persist(eq(STUDY_ID), eq(request), any(Map.class))).willReturn(ASSIGNMENT_ID);

        // when
        AssignmentCreateResponse response = assignmentCommandService.createAssignment(STUDY_ID, MEMBER_ID, request);

        // then
        assertThat(response.assignmentId()).isEqualTo(String.valueOf(ASSIGNMENT_ID));
        assertThat(response.status()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("존재하지 않는 스터디면 STUDY_NOT_FOUND 예외를 던진다")
    void createAssignment_studyNotFound() {
        // given
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> assignmentCommandService.createAssignment(STUDY_ID, MEMBER_ID, request))
            .isInstanceOf(StudyException.class)
            .satisfies(ex -> assertThat(((StudyException)ex).getErrorCode()).isEqualTo(StudyErrorCode.STUDY_NOT_FOUND));
        verify(problemSummariesClient, never()).summaries(anyList());
    }

    @Test
    @DisplayName("종료된 스터디면 STUDY_ALREADY_CLOSED 예외를 던진다")
    void createAssignment_studyClosed() {
        // given
        given(study.getStatus()).willReturn(StudyStatus.CLOSED);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));

        // when & then
        assertThatThrownBy(() -> assignmentCommandService.createAssignment(STUDY_ID, MEMBER_ID, request))
            .isInstanceOf(StudyException.class)
            .satisfies(
                ex -> assertThat(((StudyException)ex).getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ALREADY_CLOSED)
            );
        verify(problemSummariesClient, never()).summaries(anyList());
    }

    @Test
    @DisplayName("LEADER·MANAGER가 아니면 AUTH_ACCESS_DENIED 예외를 던진다")
    void createAssignment_notLeaderOrManager() {
        // given
        StudyMembership participant = mock(StudyMembership.class);
        given(participant.getRole()).willReturn(MembershipRole.MEMBER);

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.of(participant));

        // when & then
        assertThatThrownBy(() -> assignmentCommandService.createAssignment(STUDY_ID, MEMBER_ID, request))
            .isInstanceOf(StudyException.class)
            .satisfies(
                ex -> assertThat(((StudyException)ex).getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
        verify(problemSummariesClient, never()).summaries(anyList());
    }

    @Test
    @DisplayName("isAvailable=false인 문제가 있으면 STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE 예외를 던진다")
    void createAssignment_problemNotAvailable() {
        // given
        ProblemSummariesResponse summaries = new ProblemSummariesResponse(List.of(unavailableItem(PROBLEM_ID)));

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.of(leaderMembership));
        given(problemSummariesClient.summaries(List.of(PROBLEM_ID))).willReturn(summaries);

        // when & then
        assertThatThrownBy(() -> assignmentCommandService.createAssignment(STUDY_ID, MEMBER_ID, request))
            .isInstanceOf(StudyException.class)
            .satisfies(
                ex -> assertThat(((StudyException)ex).getErrorCode())
                    .isEqualTo(StudyErrorCode.STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE)
            );
        verify(assignmentPersistService, never()).persist(any(), any(), any());
    }

    @Test
    @DisplayName("summaries 응답에 요청한 문제가 없으면 STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE 예외를 던진다")
    void createAssignment_problemMissingFromResponse() {
        // given
        ProblemSummariesResponse summaries = new ProblemSummariesResponse(List.of());

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(MEMBER_ID), anyList()))
            .willReturn(Optional.of(leaderMembership));
        given(problemSummariesClient.summaries(List.of(PROBLEM_ID))).willReturn(summaries);

        // when & then
        assertThatThrownBy(() -> assignmentCommandService.createAssignment(STUDY_ID, MEMBER_ID, request))
            .isInstanceOf(StudyException.class)
            .satisfies(
                ex -> assertThat(((StudyException)ex).getErrorCode())
                    .isEqualTo(StudyErrorCode.STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE)
            );
    }
}
