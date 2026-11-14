package com.backtoback.study.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
import com.backtoback.study.assignment.client.ProblemSummaryItem;
import com.backtoback.study.assignment.dto.AssignmentCreateRequest;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * AssignmentPersistService.persist() 권한 재확인 로직을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AssignmentPersistServiceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long MEMBER_ID = 10L;
    private static final Long PROBLEM_ID = 301L;
    private static final OffsetDateTime STARTS_AT
        = OffsetDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneOffset.of("+09:00"));
    private static final OffsetDateTime DEADLINE_AT
        = OffsetDateTime.of(2026, 11, 17, 23, 59, 59, 0, ZoneOffset.of("+09:00"));

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentProblemRepository assignmentProblemRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private StudyRepository studyRepository;
    @Mock
    private StudyMembershipRepository membershipRepository;

    @InjectMocks
    private AssignmentPersistService persistService;

    private AssignmentCreateRequest request;

    @BeforeEach
    void setUp() {
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

    @Test
    @DisplayName("LEADER 권한이면 저장이 완료된다")
    void persist_success_whenLeader() throws Exception {
        // given
        StudyMembership requester = mock(StudyMembership.class);
        given(requester.getRole()).willReturn(MembershipRole.LEADER);
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(mock(Study.class)));
        given(
            membershipRepository
                .findByStudyIdAndMemberIdAndStatusWithLock(STUDY_ID, MEMBER_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(requester));
        given(objectMapper.writeValueAsString(any())).willReturn("[]");

        // when & then
        assertThatNoException()
            .isThrownBy(
                () -> persistService
                    .persist(STUDY_ID, MEMBER_ID, request, Map.of(PROBLEM_ID, availableItem(PROBLEM_ID)))
            );
        verify(assignmentRepository).save(any());
    }

    @Test
    @DisplayName("잠금 취득 시 스터디가 없으면 STUDY_NOT_FOUND 예외를 던진다")
    void persist_throwsStudyNotFound_whenStudyMissingUnderLock() {
        // given
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> persistService.persist(STUDY_ID, MEMBER_ID, request, Map.of()))
            .isInstanceOf(StudyException.class)
            .satisfies(ex -> assertThat(((StudyException)ex).getErrorCode()).isEqualTo(StudyErrorCode.STUDY_NOT_FOUND));
    }

    @Test
    @DisplayName("잠금 취득 후 멤버십이 없으면 AUTH_ACCESS_DENIED 예외를 던진다")
    void persist_throwsAuthAccessDenied_whenMembershipMissingUnderLock() {
        // given
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(mock(Study.class)));
        given(
            membershipRepository
                .findByStudyIdAndMemberIdAndStatusWithLock(STUDY_ID, MEMBER_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> persistService.persist(STUDY_ID, MEMBER_ID, request, Map.of()))
            .isInstanceOf(StudyException.class)
            .satisfies(
                ex -> assertThat(((StudyException)ex).getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("잠금 취득 후 역할이 MEMBER이면 AUTH_ACCESS_DENIED 예외를 던진다")
    void persist_throwsAuthAccessDenied_whenRoleIsNotLeaderOrManager() {
        // given
        StudyMembership requester = mock(StudyMembership.class);
        given(requester.getRole()).willReturn(MembershipRole.MEMBER);
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(mock(Study.class)));
        given(
            membershipRepository
                .findByStudyIdAndMemberIdAndStatusWithLock(STUDY_ID, MEMBER_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(requester));

        // when & then
        assertThatThrownBy(() -> persistService.persist(STUDY_ID, MEMBER_ID, request, Map.of()))
            .isInstanceOf(StudyException.class)
            .satisfies(
                ex -> assertThat(((StudyException)ex).getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }
}
