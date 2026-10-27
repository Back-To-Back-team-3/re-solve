package com.backtoback.study.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.study.discovery.dto.StudyDetailResponse;
import com.backtoback.study.discovery.dto.StudyMyListResponse;
import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMemberQuota;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMemberQuotaRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * 선택 필터가 모두 없거나 모두 있어도 예외 없이 조회되고, 조회 결과가 응답 DTO로 올바르게 바뀌는지 검증한다.
 * {@code Specification.and(null)}이 예외를 던지는 버전 차이 회귀를 잡기 위한 테스트다.
 * 상세 조회(API 명세서 5.1.2)·내 스터디 목록(API 명세서 5.1.3)의 정상·예외 시나리오도 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class StudyQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 10, 0);

    private static final List<MembershipStatus> IN_PROGRESS_STATUSES
        = List.of(MembershipStatus.PENDING, MembershipStatus.APPROVED);

    @Mock
    private StudyRepository studyRepository;

    @Mock
    private StudyMembershipRepository studyMembershipRepository;

    @Mock
    private StudyMemberQuotaRepository studyMemberQuotaRepository;

    @Mock
    private MemberReplicaRepository memberReplicaRepository;

    private StudyQueryService service;
    private Pageable pageable;

    @BeforeEach
    void setUp() {
        service
            = new StudyQueryService(
                studyRepository,
                studyMembershipRepository,
                studyMemberQuotaRepository,
                memberReplicaRepository
            );
        pageable = PageRequest.of(0, 20);
    }

    @Test
    @DisplayName("선택 필터가 모두 없어도 예외 없이 RECRUITING 스터디를 조회하고 응답 DTO로 바꾼다")
    void searchesWithoutOptionalFilters() {
        // given
        Study study = recruitingStudy();
        given(studyRepository.findAll(any(Specification.class), eq(pageable)))
            .willReturn(new PageImpl<>(List.of(study), pageable, 1));

        // when
        Page<StudySummaryResponse> result = service.searchStudies(null, null, null, null, null, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        StudySummaryResponse response = result.getContent().getFirst();
        assertThat(response.studyId()).isEqualTo("1");
        assertThat(response.name()).isEqualTo("코딩테스트 스터디");
        assertThat(response.goal()).isEqualTo("EMPLOYMENT");
    }

    @Test
    @DisplayName("선택 필터를 모두 지정해도 예외 없이 조회한다")
    void searchesWithAllOptionalFilters() {
        // given
        Study study = recruitingStudy();
        given(studyRepository.findAll(any(Specification.class), eq(pageable)))
            .willReturn(new PageImpl<>(List.of(study), pageable, 1));

        // when
        Page<StudySummaryResponse> result
            = service.searchStudies("코딩테스트", "EMPLOYMENT", "LV2", "java", "EVENING", pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("존재하지 않는 studyId를 조회하면 STUDY_NOT_FOUND를 던진다")
    void getStudyDetailThrowsWhenStudyNotFound() {
        // given
        given(studyRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getStudyDetail(99L, null))
            .isInstanceOfSatisfying(
                StudyException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_NOT_FOUND)
            );
    }

    @Test
    @DisplayName("리더 복제본과 내 가입이 모두 있으면 상세 응답에 함께 담는다")
    void getStudyDetailIncludesLeaderAndMyMembership() {
        // given
        Study study = recruitingStudy();
        given(studyRepository.findById(1L)).willReturn(Optional.of(study));

        StudyMembership leaderMembership
            = StudyMembership.apply(1L, 2L, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        given(
            studyMembershipRepository
                .findByStudyIdAndRoleAndStatus(1L, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));

        MemberReplica leaderReplica = MemberReplica.sync(2L, "leader1", null, MemberStatus.ACTIVE, 1L, NOW);
        given(memberReplicaRepository.findByMemberId(2L)).willReturn(Optional.of(leaderReplica));

        StudyMembership myMembership
            = StudyMembership.apply(1L, 7L, MembershipRole.MEMBER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(myMembership, "id", 55L);
        given(studyMembershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(1L), eq(7L), any()))
            .willReturn(Optional.of(myMembership));

        // when
        StudyDetailResponse response = service.getStudyDetail(1L, 7L);

        // then
        assertThat(response.leader()).isNotNull();
        assertThat(response.leader().nickname()).isEqualTo("leader1");
        assertThat(response.myMembership()).isNotNull();
        assertThat(response.myMembership().membershipId()).isEqualTo("55");
    }

    @Test
    @DisplayName("리더 복제본이 동기화되지 않았으면 leader가 null이다")
    void getStudyDetailLeaderNullWhenReplicaMissing() {
        // given
        Study study = recruitingStudy();
        given(studyRepository.findById(1L)).willReturn(Optional.of(study));

        StudyMembership leaderMembership
            = StudyMembership.apply(1L, 2L, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        given(
            studyMembershipRepository
                .findByStudyIdAndRoleAndStatus(1L, MembershipRole.LEADER, MembershipStatus.APPROVED)
        ).willReturn(Optional.of(leaderMembership));
        given(memberReplicaRepository.findByMemberId(2L)).willReturn(Optional.empty());

        // when
        StudyDetailResponse response = service.getStudyDetail(1L, null);

        // then
        assertThat(response.leader()).isNull();
        assertThat(response.myMembership()).isNull();
    }

    @Test
    @DisplayName("비로그인이면 내 가입 조회를 하지 않고 myMembership이 null이다")
    void getStudyDetailSkipsMyMembershipLookupWhenAnonymous() {
        // given
        Study study = recruitingStudy();
        given(studyRepository.findById(1L)).willReturn(Optional.of(study));
        given(studyMembershipRepository.findByStudyIdAndRoleAndStatus(any(), any(), any()))
            .willReturn(Optional.empty());

        // when
        StudyDetailResponse response = service.getStudyDetail(1L, null);

        // then
        assertThat(response.myMembership()).isNull();
        verify(studyMembershipRepository, never()).findByStudyIdAndMemberIdAndStatusIn(any(), any(), any());
    }

    @Test
    @DisplayName("필터 없이 조회하면 PENDING·APPROVED 가입을 모두 반환하고 참여 카운터를 포함한다")
    void getMyStudiesReturnsInProgressMembershipsWithActiveJoinCount() {
        // given
        StudyMembership membership = myMembership(55L, 1L, 7L, MembershipRole.MEMBER, MembershipStatus.APPROVED);
        Page<StudyMembership> membershipPage = new PageImpl<>(List.of(membership), pageable, 1);
        given(studyMembershipRepository.findByMemberIdAndStatusIn(eq(7L), eq(IN_PROGRESS_STATUSES), eq(pageable)))
            .willReturn(membershipPage);

        Study study = recruitingStudy();
        given(studyRepository.findAllById(List.of(1L))).willReturn(List.of(study));

        StudyMemberQuota quota = StudyMemberQuota.init(7L);
        ReflectionTestUtils.setField(quota, "activeCount", 2);
        given(studyMemberQuotaRepository.findByMemberId(7L)).willReturn(Optional.of(quota));

        // when
        StudyMyListResponse response = service.getMyStudies(7L, null, pageable);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().studyId()).isEqualTo(1L);
        assertThat(response.content().getFirst().membershipId()).isEqualTo(55L);
        assertThat(response.activeJoinCount()).isEqualTo(2);
        assertThat(response.activeJoinLimit()).isEqualTo(5);
    }

    @Test
    @DisplayName("status=PENDING만 지정하면 PENDING 가입만 조회한다")
    void getMyStudiesFiltersByPendingStatus() {
        // given
        given(
            studyMembershipRepository
                .findByMemberIdAndStatusIn(eq(7L), eq(List.of(MembershipStatus.PENDING)), eq(pageable))
        ).willReturn(new PageImpl<>(List.of()));
        given(studyMemberQuotaRepository.findByMemberId(7L)).willReturn(Optional.empty());

        // when
        service.getMyStudies(7L, MembershipStatus.PENDING, pageable);

        // then
        verify(studyMembershipRepository)
            .findByMemberIdAndStatusIn(eq(7L), eq(List.of(MembershipStatus.PENDING)), eq(pageable));
    }

    @Test
    @DisplayName("status가 PENDING·APPROVED가 아니면 둘 다 조회한다")
    void getMyStudiesFallsBackToBothStatusesForOtherValues() {
        // given
        given(studyMembershipRepository.findByMemberIdAndStatusIn(eq(7L), eq(IN_PROGRESS_STATUSES), eq(pageable)))
            .willReturn(new PageImpl<>(List.of()));
        given(studyMemberQuotaRepository.findByMemberId(7L)).willReturn(Optional.empty());

        // when
        service.getMyStudies(7L, MembershipStatus.LEFT, pageable);

        // then
        verify(studyMembershipRepository).findByMemberIdAndStatusIn(eq(7L), eq(IN_PROGRESS_STATUSES), eq(pageable));
    }

    @Test
    @DisplayName("참여 카운터 행이 없으면 activeJoinCount는 0이다")
    void getMyStudiesDefaultsActiveJoinCountToZeroWhenQuotaMissing() {
        // given
        given(studyMembershipRepository.findByMemberIdAndStatusIn(eq(7L), eq(IN_PROGRESS_STATUSES), eq(pageable)))
            .willReturn(new PageImpl<>(List.of()));
        given(studyMemberQuotaRepository.findByMemberId(7L)).willReturn(Optional.empty());

        // when
        StudyMyListResponse response = service.getMyStudies(7L, null, pageable);

        // then
        assertThat(response.activeJoinCount()).isZero();
    }

    private StudyMembership myMembership(
        Long membershipId,
        Long studyId,
        Long memberId,
        MembershipRole role,
        MembershipStatus status
    ) {
        StudyMembership membership = StudyMembership.apply(studyId, memberId, role, status, NOW, null, NOW);
        ReflectionTestUtils.setField(membership, "id", membershipId);
        return membership;
    }

    private Study recruitingStudy() {
        Study study
            = Study
                .recruit(
                    "코딩테스트 스터디",
                    "취업 준비 알고리즘 스터디",
                    "EMPLOYMENT",
                    "LV2",
                    "java",
                    "EVENING",
                    10,
                    JoinType.APPROVAL,
                    true
                );
        ReflectionTestUtils.setField(study, "id", 1L);
        return study;
    }
}
