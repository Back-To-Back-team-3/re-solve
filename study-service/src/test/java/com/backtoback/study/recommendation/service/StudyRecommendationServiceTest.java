package com.backtoback.study.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

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

import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.recommendation.domain.RecommendationMode;
import com.backtoback.study.recommendation.dto.StudyRecommendationPageResponse;
import com.backtoback.study.recommendation.dto.StudyRecommendationResponse;
import com.backtoback.study.recommendation.repository.PopularStudyRow;
import com.backtoback.study.recommendation.repository.StudyRecommendationQueryRepository;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

@ExtendWith(MockitoExtension.class)
class StudyRecommendationServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final Pageable PAGEABLE = PageRequest.of(0, 20);

    @Mock
    private StudyRecommendationQueryRepository studyRecommendationQueryRepository;

    @Mock
    private MemberLearningProfileReplicaRepository memberLearningProfileReplicaRepository;

    private StudyRecommendationService service;

    @BeforeEach
    void setUp() {
        service
            = new StudyRecommendationService(
                studyRecommendationQueryRepository,
                memberLearningProfileReplicaRepository,
                Clock.fixed(NOW, SEOUL)
            );
    }

    @Test
    @DisplayName("학습 프로필 복제본이 없으면 isProfileRequired를 true로 반환한다")
    void getRecommendations_noProfile_profileRequired() {
        given(studyRecommendationQueryRepository.findPopularStudies(eq(MEMBER_ID), any(), eq(PAGEABLE)))
            .willReturn(Page.empty(PAGEABLE));
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID))).willReturn(List.of());

        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        assertThat(response.isProfileRequired()).isTrue();
    }

    @Test
    @DisplayName("학습 프로필 복제본이 있으면 isProfileRequired를 false로 반환한다")
    void getRecommendations_profileExists_profileNotRequired() {
        given(studyRecommendationQueryRepository.findPopularStudies(eq(MEMBER_ID), any(), eq(PAGEABLE)))
            .willReturn(Page.empty(PAGEABLE));
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID)))
            .willReturn(List.of(mock(MemberLearningProfileReplica.class)));

        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        assertThat(response.isProfileRequired()).isFalse();
    }

    @Test
    @DisplayName("조회 결과가 있으면 POPULAR 모드로 점수 없이 최근 활동 근거를 붙여 반환한다")
    void getRecommendations_rowsExist_returnsPopularItems() {
        Page<PopularStudyRow> rows
            = new PageImpl<>(List.of(new Row(12L, "코딩테스트 스터디", 5, 10, "RECRUITING", 3L)), PAGEABLE, 1);
        given(studyRecommendationQueryRepository.findPopularStudies(eq(MEMBER_ID), any(), eq(PAGEABLE)))
            .willReturn(rows);
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID))).willReturn(List.of());

        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        assertThat(response.mode()).isEqualTo(RecommendationMode.POPULAR);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
        StudyRecommendationResponse item = response.content().get(0);
        assertThat(item.studyId()).isEqualTo("12");
        assertThat(item.name()).isEqualTo("코딩테스트 스터디");
        assertThat(item.score()).isNull();
        assertThat(item.reasons()).containsExactly("최근 활동이 많은 스터디");
        assertThat(item.memberCount()).isEqualTo(5);
        assertThat(item.capacity()).isEqualTo(10);
        assertThat(item.status()).isEqualTo(StudyStatus.RECRUITING);
    }

    @Test
    @DisplayName("추천 대상이 없으면 POPULAR 모드의 빈 목록을 반환한다")
    void getRecommendations_noRows_returnsEmptyPopular() {
        given(studyRecommendationQueryRepository.findPopularStudies(eq(MEMBER_ID), any(), eq(PAGEABLE)))
            .willReturn(Page.empty(PAGEABLE));
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID))).willReturn(List.of());

        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        assertThat(response.mode()).isEqualTo(RecommendationMode.POPULAR);
        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    @DisplayName("활동 집계 시작 시각은 현재 시각에서 7일 전으로 조회한다")
    void getRecommendations_activityWindow_sevenDaysBeforeNow() {
        given(studyRecommendationQueryRepository.findPopularStudies(eq(MEMBER_ID), any(), eq(PAGEABLE)))
            .willReturn(Page.empty(PAGEABLE));
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID))).willReturn(List.of());

        service.getRecommendations(MEMBER_ID, PAGEABLE);

        LocalDateTime expectedSince = LocalDateTime.ofInstant(NOW, SEOUL).minusDays(7);
        then(studyRecommendationQueryRepository).should().findPopularStudies(MEMBER_ID, expectedSince, PAGEABLE);
    }

    private record Row(
        Long studyId,
        String name,
        Integer memberCount,
        Integer capacity,
        String status,
        Long activityCount
    ) implements PopularStudyRow {

        @Override
        public Long getStudyId() {
            return studyId;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Integer getMemberCount() {
            return memberCount;
        }

        @Override
        public Integer getCapacity() {
            return capacity;
        }

        @Override
        public String getStatus() {
            return status;
        }

        @Override
        public Long getActivityCount() {
            return activityCount;
        }
    }
}
