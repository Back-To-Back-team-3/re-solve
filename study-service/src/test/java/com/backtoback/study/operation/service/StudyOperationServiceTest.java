package com.backtoback.study.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
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
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.operation.dto.StudyUpdateRequest;
import com.backtoback.study.operation.dto.StudyUpdateResponse;

/**
 * 스터디 설정 수정 정상·예외 시나리오를 검증한다(API 명세서 §5.3.1).
 */
@ExtendWith(MockitoExtension.class)
class StudyOperationServiceTest {

    private static final long STUDY_ID = 1L;
    private static final long LEADER_ID = 7L;
    private static final long VERSION = 3L;

    @Mock
    private StudyRepository studyRepository;
    @Mock
    private StudyMembershipRepository membershipRepository;

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
}
