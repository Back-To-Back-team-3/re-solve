package com.backtoback.study.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.operation.dto.LeaderDelegationRequest;
import com.backtoback.study.operation.dto.LeaderDelegationResponse;
import com.backtoback.study.operation.dto.StudyRoleChangeRequest;
import com.backtoback.study.operation.dto.StudyRoleChangeResponse;
import com.backtoback.study.operation.dto.StudyUpdateRequest;
import com.backtoback.study.operation.dto.StudyUpdateResponse;

/**
 * 스터디 운영 서비스 정상·예외 시나리오를 검증한다(API 명세서 §5.3).
 */
@ExtendWith(MockitoExtension.class)
class StudyOperationServiceTest {

    private static final long STUDY_ID = 1L;
    private static final long LEADER_MEMBERSHIP_ID = 5L;
    private static final long LEADER_ID = 7L;
    private static final long TARGET_MEMBERSHIP_ID = 10L;
    private static final long TARGET_MEMBER_ID = 20L;
    private static final long VERSION = 3L;

    @Mock
    private StudyRepository studyRepository;
    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private StudyMembershipHistoryRepository historyRepository;
    @Mock
    private Clock clock;

    @InjectMocks
    private StudyOperationService studyOperationService;

    private Study study;
    private StudyMembership leaderMembership;

    @BeforeEach
    void setUp() {
        study = Study.recruit("스터디", "기존 설명", "EMPLOYMENT", "LV2", "java", "EVENING", 6, JoinType.APPROVAL, true);
        ReflectionTestUtils.setField(study, "id", STUDY_ID);
        ReflectionTestUtils.setField(study, "version", VERSION);
        ReflectionTestUtils.setField(study, "memberCount", 2);

        leaderMembership
            = StudyMembership
                .apply(
                    STUDY_ID,
                    LEADER_ID,
                    MembershipRole.LEADER,
                    MembershipStatus.APPROVED,
                    LocalDateTime.now(),
                    null,
                    LocalDateTime.now()
                );
        ReflectionTestUtils.setField(leaderMembership, "id", LEADER_MEMBERSHIP_ID);

        // history 기록 경로를 거치지 않는 테스트에서도 주입된 Clock이 있어야 하므로 lenient로 등록한다.
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-11-08T09:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
    }

    private StudyMembership memberMembership() {
        StudyMembership membership
            = StudyMembership
                .apply(
                    STUDY_ID,
                    TARGET_MEMBER_ID,
                    MembershipRole.MEMBER,
                    MembershipStatus.APPROVED,
                    LocalDateTime.now(),
                    null,
                    LocalDateTime.now()
                );
        ReflectionTestUtils.setField(membership, "id", TARGET_MEMBERSHIP_ID);
        return membership;
    }

    private StudyMembership managerMembership() {
        StudyMembership membership
            = StudyMembership
                .apply(
                    STUDY_ID,
                    TARGET_MEMBER_ID,
                    MembershipRole.MANAGER,
                    MembershipStatus.APPROVED,
                    LocalDateTime.now(),
                    null,
                    LocalDateTime.now()
                );
        ReflectionTestUtils.setField(membership, "id", TARGET_MEMBERSHIP_ID);
        return membership;
    }

    @Test
    @DisplayName("§5.3.1 LEADER가 description만 변경하면 version+1을 반환한다")
    void updateStudy_success() {
        // given
        StudyUpdateRequest request = new StudyUpdateRequest("새 설명", null, null, null, VERSION);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(studyRepository.updateSettings(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyBoolean()))
            .willReturn(1);

        // when
        StudyUpdateResponse result = studyOperationService.updateStudy(STUDY_ID, LEADER_ID, request);

        // then
        assertThat(result.studyId()).isEqualTo(String.valueOf(STUDY_ID));
        assertThat(result.version()).isEqualTo(VERSION + 1);
    }

    @Test
    @DisplayName("§5.3.1 존재하지 않는 studyId이면 STUDY_NOT_FOUND 예외를 던진다")
    void updateStudy_studyNotFound_throwsStudyNotFound() {
        // given
        StudyUpdateRequest request = new StudyUpdateRequest(null, null, null, null, VERSION);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> studyOperationService.updateStudy(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_NOT_FOUND)
            );
    }

    @Test
    @DisplayName("§5.3.1 LEADER 멤버십이 없으면 AUTH_ACCESS_DENIED 예외를 던진다")
    void updateStudy_leaderNotFound_throwsAccessDenied() {
        // given
        StudyUpdateRequest request = new StudyUpdateRequest(null, null, null, null, VERSION);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> studyOperationService.updateStudy(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("§5.3.1 요청자가 LEADER가 아니면 AUTH_ACCESS_DENIED 예외를 던진다")
    void updateStudy_notLeader_throwsAccessDenied() {
        // given
        long otherMemberId = 99L;
        StudyUpdateRequest request = new StudyUpdateRequest(null, null, null, null, VERSION);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));

        // when & then
        assertThatThrownBy(() -> studyOperationService.updateStudy(STUDY_ID, otherMemberId, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("§5.3.1 APPROVAL→INSTANT 전환 시 PENDING 신청이 있으면 STUDY_PENDING_APPLICATIONS_EXIST 예외를 던진다")
    void updateStudy_approvalToInstantWithPending_throwsPendingApplicationsExist() {
        // given
        StudyUpdateRequest request = new StudyUpdateRequest(null, JoinType.INSTANT, null, null, VERSION);
        StudyMembership pending
            = StudyMembership
                .apply(
                    STUDY_ID,
                    20L,
                    MembershipRole.MEMBER,
                    MembershipStatus.PENDING,
                    LocalDateTime.now(),
                    LocalDateTime.now().plusDays(3),
                    null
                );
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(membershipRepository.findAllByStudyIdAndStatus(STUDY_ID, MembershipStatus.PENDING))
            .willReturn(List.of(pending));

        // when & then
        assertThatThrownBy(() -> studyOperationService.updateStudy(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_PENDING_APPLICATIONS_EXIST)
            );
    }

    @Test
    @DisplayName("§5.3.1 UPDATE 0행이고 재조회 시 CLOSED이면 STUDY_ALREADY_CLOSED 예외를 던진다")
    void updateStudy_rows0AndClosed_throwsAlreadyClosed() {
        // given
        StudyUpdateRequest request = new StudyUpdateRequest("설명", null, null, null, VERSION);
        Study freshStudy
            = Study.recruit("스터디", "설명", "EMPLOYMENT", "LV2", "java", "EVENING", 6, JoinType.APPROVAL, true);
        ReflectionTestUtils.setField(freshStudy, "id", STUDY_ID);
        ReflectionTestUtils.setField(freshStudy, "version", VERSION);
        ReflectionTestUtils.setField(freshStudy, "status", StudyStatus.CLOSED);

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study)).willReturn(Optional.of(freshStudy));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(studyRepository.updateSettings(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyBoolean()))
            .willReturn(0);

        // when & then
        assertThatThrownBy(() -> studyOperationService.updateStudy(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ALREADY_CLOSED)
            );
    }

    @Test
    @DisplayName("§5.3.1 UPDATE 0행이고 version이 다르면 STUDY_UPDATE_CONFLICT 예외를 던진다")
    void updateStudy_rows0AndVersionMismatch_throwsUpdateConflict() {
        // given
        StudyUpdateRequest request = new StudyUpdateRequest("설명", null, null, null, VERSION);
        Study freshStudy
            = Study.recruit("스터디", "설명", "EMPLOYMENT", "LV2", "java", "EVENING", 6, JoinType.APPROVAL, true);
        ReflectionTestUtils.setField(freshStudy, "id", STUDY_ID);
        ReflectionTestUtils.setField(freshStudy, "version", VERSION + 1);

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study)).willReturn(Optional.of(freshStudy));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(studyRepository.updateSettings(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyBoolean()))
            .willReturn(0);

        // when & then
        assertThatThrownBy(() -> studyOperationService.updateStudy(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_UPDATE_CONFLICT)
            );
    }

    @Test
    @DisplayName("§5.3.1 UPDATE 0행이고 version이 같고 CLOSED가 아니면 STUDY_CAPACITY_TOO_SMALL 예외를 던진다")
    void updateStudy_rows0AndSameVersion_throwsCapacityTooSmall() {
        // given
        StudyUpdateRequest request = new StudyUpdateRequest(null, null, 1, null, VERSION);
        Study freshStudy
            = Study.recruit("스터디", "설명", "EMPLOYMENT", "LV2", "java", "EVENING", 6, JoinType.APPROVAL, true);
        ReflectionTestUtils.setField(freshStudy, "id", STUDY_ID);
        ReflectionTestUtils.setField(freshStudy, "version", VERSION);

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study)).willReturn(Optional.of(freshStudy));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(studyRepository.updateSettings(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyBoolean()))
            .willReturn(0);

        // when & then
        assertThatThrownBy(() -> studyOperationService.updateStudy(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_CAPACITY_TOO_SMALL)
            );
    }

    // ── §5.3.2 changeRole ──────────────────────────────────────────────────

    @Test
    @DisplayName("§5.3.2 MEMBER를 MANAGER로 임명하면 200과 역할을 반환한다")
    void changeRole_memberToManager_success() {
        // given
        StudyRoleChangeRequest request = new StudyRoleChangeRequest(MembershipRole.MANAGER);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(memberMembership()));
        given(studyRepository.incrementManagerCountIfNotFull(STUDY_ID)).willReturn(1);

        // when
        StudyRoleChangeResponse result
            = studyOperationService.changeRole(STUDY_ID, TARGET_MEMBERSHIP_ID, LEADER_ID, request);

        // then
        assertThat(result.membershipId()).isEqualTo(String.valueOf(TARGET_MEMBERSHIP_ID));
        assertThat(result.role()).isEqualTo("MANAGER");
        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("§5.3.2 MANAGER를 MEMBER로 해제하면 200과 역할을 반환한다")
    void changeRole_managerToMember_success() {
        // given
        StudyRoleChangeRequest request = new StudyRoleChangeRequest(MembershipRole.MEMBER);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(managerMembership()));

        // when
        StudyRoleChangeResponse result
            = studyOperationService.changeRole(STUDY_ID, TARGET_MEMBERSHIP_ID, LEADER_ID, request);

        // then
        assertThat(result.role()).isEqualTo("MEMBER");
        verify(studyRepository).decrementManagerCount(STUDY_ID);
        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("§5.3.2 운영진 2명 상한 초과 임명 시 STUDY_MANAGER_LIMIT_EXCEEDED 예외를 던진다")
    void changeRole_managerLimitExceeded_throwsManagerLimitExceeded() {
        // given
        StudyRoleChangeRequest request = new StudyRoleChangeRequest(MembershipRole.MANAGER);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(memberMembership()));
        given(studyRepository.incrementManagerCountIfNotFull(STUDY_ID)).willReturn(0);

        // when & then
        assertThatThrownBy(() -> studyOperationService.changeRole(STUDY_ID, TARGET_MEMBERSHIP_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_MANAGER_LIMIT_EXCEEDED)
            );
    }

    @Test
    @DisplayName("§5.3.2 대상 역할이 LEADER이면 STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED 예외를 던진다")
    void changeRole_targetIsLeader_throwsLeaderRoleChangeNotAllowed() {
        // given
        StudyRoleChangeRequest request = new StudyRoleChangeRequest(MembershipRole.MEMBER);
        StudyMembership anotherLeader
            = StudyMembership
                .apply(
                    STUDY_ID,
                    TARGET_MEMBER_ID,
                    MembershipRole.LEADER,
                    MembershipStatus.APPROVED,
                    LocalDateTime.now(),
                    null,
                    LocalDateTime.now()
                );
        ReflectionTestUtils.setField(anotherLeader, "id", TARGET_MEMBERSHIP_ID);

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(anotherLeader));

        // when & then
        assertThatThrownBy(() -> studyOperationService.changeRole(STUDY_ID, TARGET_MEMBERSHIP_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED)
            );
    }

    @Test
    @DisplayName("§5.3.2 요청 역할이 LEADER이면 STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED 예외를 던진다")
    void changeRole_requestRoleIsLeader_throwsLeaderRoleChangeNotAllowed() {
        // given
        StudyRoleChangeRequest request = new StudyRoleChangeRequest(MembershipRole.LEADER);

        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(memberMembership()));

        // when & then
        assertThatThrownBy(() -> studyOperationService.changeRole(STUDY_ID, TARGET_MEMBERSHIP_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED)
            );
    }

    @Test
    @DisplayName("§5.3.2 같은 역할로 요청하면 histories를 기록하지 않고 멱등 응답을 반환한다")
    void changeRole_sameRole_idempotentNoHistory() {
        // given
        StudyRoleChangeRequest request = new StudyRoleChangeRequest(MembershipRole.MEMBER);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(memberMembership()));

        // when
        StudyRoleChangeResponse result
            = studyOperationService.changeRole(STUDY_ID, TARGET_MEMBERSHIP_ID, LEADER_ID, request);

        // then
        assertThat(result.role()).isEqualTo("MEMBER");
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("§5.3.2 CLOSED 스터디에서 changeRole 시 STUDY_ALREADY_CLOSED 예외를 던진다")
    void changeRole_closedStudy_throwsAlreadyClosed() {
        // given
        ReflectionTestUtils.setField(study, "status", StudyStatus.CLOSED);
        StudyRoleChangeRequest request = new StudyRoleChangeRequest(MembershipRole.MANAGER);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));

        // when & then
        assertThatThrownBy(() -> studyOperationService.changeRole(STUDY_ID, TARGET_MEMBERSHIP_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ALREADY_CLOSED)
            );
    }

    // ── §5.3.3 delegateLeader ──────────────────────────────────────────────

    @Test
    @DisplayName("§5.3.3 LEADER가 MANAGER를 위임하면 manager_count 불변·histories 2건 저장")
    void delegateLeader_toManager_success() {
        // given
        LeaderDelegationRequest request = new LeaderDelegationRequest(TARGET_MEMBERSHIP_ID);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(managerMembership()));
        given(membershipRepository.demoteLeader(LEADER_MEMBERSHIP_ID)).willReturn(1);
        given(membershipRepository.promoteToLeader(TARGET_MEMBERSHIP_ID)).willReturn(1);

        // when
        LeaderDelegationResponse result = studyOperationService.delegateLeader(STUDY_ID, LEADER_ID, request);

        // then
        assertThat(result.leaderMembershipId()).isEqualTo(String.valueOf(TARGET_MEMBERSHIP_ID));
        verify(studyRepository, never()).incrementManagerCountIfNotFull(anyLong());
        verify(studyRepository).bumpVersion(STUDY_ID);
        verify(historyRepository, never()).save(null);
    }

    @Test
    @DisplayName("§5.3.3 LEADER가 MEMBER를 위임하면 manager_count+1·histories 2건 저장")
    void delegateLeader_toMember_incrementsManagerCount() {
        // given
        LeaderDelegationRequest request = new LeaderDelegationRequest(TARGET_MEMBERSHIP_ID);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(memberMembership()));
        given(membershipRepository.demoteLeader(LEADER_MEMBERSHIP_ID)).willReturn(1);
        given(membershipRepository.promoteToLeader(TARGET_MEMBERSHIP_ID)).willReturn(1);
        given(studyRepository.incrementManagerCountIfNotFull(STUDY_ID)).willReturn(1);

        // when
        studyOperationService.delegateLeader(STUDY_ID, LEADER_ID, request);

        // then
        verify(studyRepository).incrementManagerCountIfNotFull(STUDY_ID);
    }

    @Test
    @DisplayName("§5.3.3 운영진 2명인 스터디에서 MEMBER 위임 시 STUDY_MANAGER_LIMIT_EXCEEDED 예외를 던진다")
    void delegateLeader_toMemberWhenManagerFull_throwsManagerLimitExceeded() {
        // given
        LeaderDelegationRequest request = new LeaderDelegationRequest(TARGET_MEMBERSHIP_ID);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(memberMembership()));
        given(membershipRepository.demoteLeader(LEADER_MEMBERSHIP_ID)).willReturn(1);
        given(membershipRepository.promoteToLeader(TARGET_MEMBERSHIP_ID)).willReturn(1);
        given(studyRepository.incrementManagerCountIfNotFull(STUDY_ID)).willReturn(0);

        // when & then
        assertThatThrownBy(() -> studyOperationService.delegateLeader(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_MANAGER_LIMIT_EXCEEDED)
            );
    }

    @Test
    @DisplayName("§5.3.3 demoteLeader 0행이면 AUTH_ACCESS_DENIED 예외를 던진다")
    void delegateLeader_demoteLeaderRows0_throwsAccessDenied() {
        // given
        LeaderDelegationRequest request = new LeaderDelegationRequest(TARGET_MEMBERSHIP_ID);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(memberMembership()));
        given(membershipRepository.demoteLeader(LEADER_MEMBERSHIP_ID)).willReturn(0);

        // when & then
        assertThatThrownBy(() -> studyOperationService.delegateLeader(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("§5.3.3 promoteToLeader 0행이면 STUDY_MEMBERSHIP_NOT_FOUND 예외를 던진다")
    void delegateLeader_promoteRows0_throwsMembershipNotFound() {
        // given
        LeaderDelegationRequest request = new LeaderDelegationRequest(TARGET_MEMBERSHIP_ID);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(
            membershipRepository.findByIdAndStudyIdAndStatus(TARGET_MEMBERSHIP_ID, STUDY_ID, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(memberMembership()));
        given(membershipRepository.demoteLeader(LEADER_MEMBERSHIP_ID)).willReturn(1);
        given(membershipRepository.promoteToLeader(TARGET_MEMBERSHIP_ID)).willReturn(0);

        // when & then
        assertThatThrownBy(() -> studyOperationService.delegateLeader(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND)
            );
    }

    @Test
    @DisplayName("§5.3.3 본인 membershipId로 위임하면 STUDY_MEMBERSHIP_NOT_FOUND 예외를 던진다")
    void delegateLeader_selfDelegation_throwsMembershipNotFound() {
        // given
        LeaderDelegationRequest request = new LeaderDelegationRequest(LEADER_MEMBERSHIP_ID);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));
        given(
            membershipRepository
                .findByStudyIdAndRoleAndStatus(STUDY_ID, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));

        // when & then
        assertThatThrownBy(() -> studyOperationService.delegateLeader(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND)
            );
    }

    @Test
    @DisplayName("§5.3.3 CLOSED 스터디에서 위임하면 STUDY_ALREADY_CLOSED 예외를 던진다")
    void delegateLeader_closedStudy_throwsAlreadyClosed() {
        // given
        ReflectionTestUtils.setField(study, "status", StudyStatus.CLOSED);
        LeaderDelegationRequest request = new LeaderDelegationRequest(TARGET_MEMBERSHIP_ID);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(study));

        // when & then
        assertThatThrownBy(() -> studyOperationService.delegateLeader(STUDY_ID, LEADER_ID, request))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ALREADY_CLOSED)
            );
    }
}
