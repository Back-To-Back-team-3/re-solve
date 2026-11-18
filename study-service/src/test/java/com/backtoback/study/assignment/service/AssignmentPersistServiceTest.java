package com.backtoback.study.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.assignment.client.ProblemSummariesClient;
import com.backtoback.study.assignment.client.ProblemSummariesResponse;
import com.backtoback.study.assignment.client.ProblemSummaryItem;
import com.backtoback.study.assignment.dto.AssignmentCreateRequest;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;
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

    /**
     * 잠근 스터디 수준과 조회한 회차 난이도에 따라 저장 여부를 검증한다.
     *
     * @param level 저장 시점의 스터디 수준
     * @param difficulty 배정 회차 난이도
     * @param allowed 배정 허용 여부
     */
    @ParameterizedTest
    @CsvSource(
        {
            "LV1,1,true",
            "LV1,2,false",
            "LV1,3,false",
            "LV2,1,true",
            "LV2,2,true",
            "LV2,3,false",
            "LV3,1,true",
            "LV3,2,true",
            "LV3,3,true",
            "LV3,0,false",
            "LV3,4,false"
        }
    )
    @DisplayName("저장 시점의 스터디 수준별 난이도 범위를 검증하고 초과하면 저장하지 않는다")
    void persist_validatesDifficultyUnderLock(String level, int difficulty, boolean allowed) {
        stubAuthorizedStudy(level);
        ProblemSummaryItem item
            = new ProblemSummaryItem(
                PROBLEM_ID,
                true,
                99L,
                2,
                "변경된 문제",
                difficulty,
                List.of(),
                "GENERAL",
                "PUBLIC",
                "1.0",
                List.of("java")
            );

        if (allowed) {
            given(objectMapper.writeValueAsString(any())).willReturn("[]");
            assertThatNoException()
                .isThrownBy(() -> persistService.persist(STUDY_ID, MEMBER_ID, request, Map.of(PROBLEM_ID, item)));
            verify(assignmentRepository).save(any());
            verify(assignmentProblemRepository).saveAll(any());
        } else {
            assertThatThrownBy(() -> persistService.persist(STUDY_ID, MEMBER_ID, request, Map.of(PROBLEM_ID, item)))
                .isInstanceOfSatisfying(
                    StudyException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ASSIGNMENT_DIFFICULTY_EXCEEDED)
                );
            verifyNoInteractions(assignmentRepository, assignmentProblemRepository, objectMapper);
        }
    }

    /**
     * 구형 또는 알 수 없는 수준을 임의 변환하지 않는지 검증한다.
     *
     * @param level 허용되지 않은 스터디 수준
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
            "LV0",
            "MASTER",
            "UNKNOWN"
        }
    )
    @DisplayName("구형 또는 알 수 없는 수준이면 문제집을 저장하지 않는다")
    void persist_rejectsUnknownLevel(String level) {
        stubAuthorizedStudy(level);
        assertThatThrownBy(
            () -> persistService.persist(STUDY_ID, MEMBER_ID, request, Map.of(PROBLEM_ID, availableItem(PROBLEM_ID)))
        )
            .isInstanceOfSatisfying(
                StudyException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        verifyNoInteractions(assignmentRepository, assignmentProblemRepository, objectMapper);
    }

    /** 여러 문제 중 뒤쪽 문제만 초과해도 어떤 저장도 시작하지 않는지 검증한다. */
    @Test
    @DisplayName("여러 문제 중 하나라도 상한을 넘으면 문제집 전체를 저장하지 않는다")
    void persist_rejectsEntireBatch() {
        stubAuthorizedStudy("LV1");
        AssignmentCreateRequest batch
            = new AssignmentCreateRequest("문제집", "복습", null, List.of(PROBLEM_ID, 302L), false, STARTS_AT, DEADLINE_AT);
        ProblemSummaryItem harder
            = new ProblemSummaryItem(
                302L,
                true,
                100L,
                3,
                "어려운 문제",
                2,
                List.of(),
                "GENERAL",
                "PUBLIC",
                "1.0",
                List.of("java")
            );
        assertThatThrownBy(
            () -> persistService
                .persist(STUDY_ID, MEMBER_ID, batch, Map.of(PROBLEM_ID, availableItem(PROBLEM_ID), 302L, harder))
        )
            .isInstanceOfSatisfying(
                StudyException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ASSIGNMENT_DIFFICULTY_EXCEEDED)
            );
        verifyNoInteractions(assignmentRepository, assignmentProblemRepository, objectMapper);
    }

    /**
     * 외부 조회 이전 상태가 저장 단계의 최신 상태 검증을 우회하지 못하는지 검증한다.
     *
     * @param closed 외부 조회 도중 스터디 종료 여부
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            true,
            false
        }
    )
    @DisplayName("문제 요약 조회 중 종료 또는 수준 하향 시 최신 잠금 상태로 생성이 거절된다")
    void create_rechecksStateAfterExternalLookup(boolean closed) {
        Study before = Study.recruit("스터디", "설명", "목표", "LV3", "java", "EVENING", 5, JoinType.APPROVAL, true);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(before));
        StudyMembership leader = mock(StudyMembership.class);
        given(leader.getRole()).willReturn(MembershipRole.LEADER);
        given(
            membershipRepository
                .findByStudyIdAndMemberIdAndStatusIn(STUDY_ID, MEMBER_ID, List.of(MembershipStatus.APPROVED))
        ).willReturn(Optional.of(leader));
        ProblemSummariesClient client = mock(ProblemSummariesClient.class);
        given(client.summaries(request.problemIds())).willAnswer(invocation -> {
            if (closed) {
                Study after = closedStudy();
                given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(after));
            } else {
                stubAuthorizedStudy("LV1");
            }
            return new ProblemSummariesResponse(
                List
                    .of(
                        new ProblemSummaryItem(
                            PROBLEM_ID,
                            true,
                            99L,
                            2,
                            "최신 회차",
                            2,
                            List.of(),
                            "GENERAL",
                            "PUBLIC",
                            "1.0",
                            List.of("java")
                        )
                    )
            );
        });
        AssignmentCommandService command
            = new AssignmentCommandService(studyRepository, membershipRepository, client, persistService);

        assertThatThrownBy(() -> command.createAssignment(STUDY_ID, MEMBER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                ex -> assertThat(ex.getErrorCode())
                    .isEqualTo(
                        closed ? StudyErrorCode.STUDY_ALREADY_CLOSED
                            : StudyErrorCode.STUDY_ASSIGNMENT_DIFFICULTY_EXCEEDED
                    )
            );
        verify(client).summaries(request.problemIds());
        verifyNoInteractions(assignmentRepository, assignmentProblemRepository, objectMapper);
    }

    /**
     * 종료 처리된 스터디를 구성한다.
     *
     * @return 종료 상태의 스터디
     */
    private Study closedStudy() {
        Study study = Study.recruit("스터디", "설명", "목표", "LV3", "java", "EVENING", 5, JoinType.APPROVAL, true);
        ReflectionTestUtils.setField(study, "status", StudyStatus.CLOSED);
        return study;
    }

    /**
     * 저장 시점의 스터디와 승인된 운영진을 구성한다.
     *
     * @param level 잠금으로 확보할 스터디 수준
     */
    private void stubAuthorizedStudy(String level) {
        Study study = Study.recruit("스터디", "설명", "목표", level, "java", "EVENING", 5, JoinType.APPROVAL, true);
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(study));
        StudyMembership requester = mock(StudyMembership.class);
        given(requester.getRole()).willReturn(MembershipRole.MANAGER);
        given(
            membershipRepository
                .findByStudyIdAndMemberIdAndStatusWithLock(STUDY_ID, MEMBER_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(requester));
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

    /** 잠금 취득 시 이미 종료된 스터디에는 저장하지 않는지 검증한다. */
    @Test
    @DisplayName("외부 조회 후 잠근 스터디가 종료 상태이면 문제집을 저장하지 않는다")
    void persist_rejectsClosedStudyUnderLock() {
        Study study = closedStudy();
        given(studyRepository.findByIdWithLock(STUDY_ID)).willReturn(Optional.of(study));

        assertThatThrownBy(() -> persistService.persist(STUDY_ID, MEMBER_ID, request, Map.of()))
            .isInstanceOfSatisfying(
                StudyException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ALREADY_CLOSED)
            );
        verifyNoInteractions(membershipRepository, assignmentRepository, assignmentProblemRepository);
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
