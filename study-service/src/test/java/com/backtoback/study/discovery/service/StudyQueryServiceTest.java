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
import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * 선택 필터가 모두 없거나 모두 있어도 예외 없이 조회되고, 조회 결과가 응답 DTO로 올바르게 바뀌는지 검증한다.
 * {@code Specification.and(null)}이 예외를 던지는 버전 차이 회귀를 잡기 위한 테스트다.
 * 상세 조회(API 명세서 5.1.2)의 정상·예외 시나리오도 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class StudyQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 10, 0);

    @Mock
    private StudyRepository studyRepository;

    @Mock
    private StudyMembershipRepository studyMembershipRepository;

    @Mock
    private MemberReplicaRepository memberReplicaRepository;

    private StudyQueryService service;
    private Pageable pageable;

    @BeforeEach
    void setUp() {
        service = new StudyQueryService(studyRepository, studyMembershipRepository, memberReplicaRepository);
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
