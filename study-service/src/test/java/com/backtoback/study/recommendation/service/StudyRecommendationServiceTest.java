package com.backtoback.study.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.recommendation.domain.RecommendationMode;
import com.backtoback.study.recommendation.dto.StudyRecommendationPageResponse;
import com.backtoback.study.recommendation.dto.StudyRecommendationResponse;
import com.backtoback.study.recommendation.repository.PersonalizedStudyRow;
import com.backtoback.study.recommendation.repository.PopularStudyRow;
import com.backtoback.study.recommendation.repository.StudyRecommendationQueryRepository;
import com.backtoback.study.recommendation.repository.StudyRecommendationRepository;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class StudyRecommendationServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, SEOUL);
    private static final Pageable PAGEABLE = PageRequest.of(0, 20);

    @Mock
    private StudyRecommendationQueryRepository studyRecommendationQueryRepository;

    @Mock
    private StudyRecommendationRepository studyRecommendationRepository;

    @Mock
    private MemberLearningProfileReplicaRepository memberLearningProfileReplicaRepository;

    @Mock
    private StudyRecommendationRefreshService studyRecommendationRefreshService;

    private StudyRecommendationService service;

    @BeforeEach
    void setUp() {
        service
            = new StudyRecommendationService(
                studyRecommendationQueryRepository,
                studyRecommendationRepository,
                memberLearningProfileReplicaRepository,
                studyRecommendationRefreshService,
                JsonMapper.builder().build(),
                Clock.fixed(NOW, SEOUL)
            );
    }

    @Test
    @DisplayName("학습 프로필 복제본이 없으면 POPULAR 모드와 isProfileRequired true를 반환한다")
    void getRecommendations_noProfile_popularAndProfileRequired() {
        // given
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID))).willReturn(List.of());
        givenPopular(Page.empty(PAGEABLE));

        // when
        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        assertThat(response.mode()).isEqualTo(RecommendationMode.POPULAR);
        assertThat(response.isProfileRequired()).isTrue();
        then(studyRecommendationRepository).should(never()).findLatestGeneratedAt(anyLong());
    }

    @Test
    @DisplayName("복제본에 태그 레벨만 있으면 학습 프로필 입력으로 보지 않고 isProfileRequired true를 반환한다")
    void getRecommendations_tagLevelsOnly_profileRequired() {
        // given
        givenProfile(false);
        givenPopular(Page.empty(PAGEABLE));

        // when
        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        assertThat(response.mode()).isEqualTo(RecommendationMode.POPULAR);
        assertThat(response.isProfileRequired()).isTrue();
    }

    @Test
    @DisplayName("학습 프로필 입력이 있어도 저장된 추천 결과가 없으면 재계산하지 않고 POPULAR 모드를 반환한다")
    void getRecommendations_noStoredResult_popularWithoutRefresh() {
        // given
        givenProfile(true);
        given(studyRecommendationRepository.findLatestGeneratedAt(MEMBER_ID)).willReturn(Optional.empty());
        givenPopular(Page.empty(PAGEABLE));

        // when
        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        assertThat(response.mode()).isEqualTo(RecommendationMode.POPULAR);
        assertThat(response.isProfileRequired()).isFalse();
        then(studyRecommendationRefreshService).should(never()).refresh(anyLong());
    }

    @Test
    @DisplayName("저장된 추천 결과가 있으면 PERSONALIZED 모드로 점수와 추천 근거를 반환한다")
    void getRecommendations_storedResult_returnsPersonalized() {
        // given
        givenProfile(true);
        givenGeneratedAt(NOW_LOCAL.minusHours(1));
        givenPersonalized(personalizedPage());

        // when
        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        assertThat(response.mode()).isEqualTo(RecommendationMode.PERSONALIZED);
        assertThat(response.isProfileRequired()).isFalse();
        assertThat(response.totalElements()).isEqualTo(1);
        StudyRecommendationResponse item = response.content().get(0);
        assertThat(item.studyId()).isEqualTo("12");
        assertThat(item.name()).isEqualTo("코딩테스트 스터디");
        assertThat(item.score()).isEqualByComparingTo("87.00");
        assertThat(item.reasons()).containsExactly("목표 일치", "태그 레벨 비슷함", "선호 언어 같음");
        assertThat(item.memberCount()).isEqualTo(5);
        assertThat(item.capacity()).isEqualTo(10);
        assertThat(item.status()).isEqualTo(StudyStatus.RECRUITING);
        then(studyRecommendationRefreshService).should(never()).refresh(anyLong());
    }

    @Test
    @DisplayName("추천 결과가 하루보다 오래됐으면 응답 전에 다시 계산한다")
    void getRecommendations_olderThanOneDay_refreshesBeforeRead() {
        // given
        givenProfile(true);
        givenGeneratedAt(NOW_LOCAL.minusHours(24).minusNanos(1_000));
        givenPersonalized(personalizedPage());

        // when
        service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        then(studyRecommendationRefreshService).should().refresh(MEMBER_ID);
    }

    @Test
    @DisplayName("추천 결과가 정확히 하루 전에 계산됐으면 다시 계산하지 않는다")
    void getRecommendations_exactlyOneDay_doesNotRefresh() {
        // given
        givenProfile(true);
        givenGeneratedAt(NOW_LOCAL.minusHours(24));
        givenPersonalized(personalizedPage());

        // when
        service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        then(studyRecommendationRefreshService).should(never()).refresh(anyLong());
    }

    @Test
    @DisplayName("재계산이 DB 오류로 실패하면 기존 추천 결과로 응답한다")
    void getRecommendations_refreshFails_returnsExistingResult() {
        // given
        givenProfile(true);
        givenGeneratedAt(NOW_LOCAL.minusDays(2));
        willThrow(new PessimisticLockingFailureException("lock wait timeout"))
            .given(studyRecommendationRefreshService)
            .refresh(MEMBER_ID);
        givenPersonalized(personalizedPage());

        // when
        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        assertThat(response.mode()).isEqualTo(RecommendationMode.PERSONALIZED);
        assertThat(response.content()).hasSize(1);
    }

    @Test
    @DisplayName("재계산이 DB 오류가 아닌 예외로 실패하면 예외를 그대로 전파한다")
    void getRecommendations_refreshProgrammingError_propagates() {
        // given
        givenProfile(true);
        givenGeneratedAt(NOW_LOCAL.minusDays(2));
        willThrow(new IllegalStateException("unexpected")).given(studyRecommendationRefreshService).refresh(MEMBER_ID);

        // when & then
        assertThatThrownBy(() -> service.getRecommendations(MEMBER_ID, PAGEABLE))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("추천 대상 조건을 거친 저장 결과가 없으면 POPULAR 모드와 isProfileRequired false를 반환한다")
    void getRecommendations_noPersonalizedRows_fallsBackToPopular() {
        // given
        givenProfile(true);
        givenGeneratedAt(NOW_LOCAL.minusHours(1));
        givenPersonalized(Page.empty(PAGEABLE));
        Page<PopularStudyRow> popularRows
            = new PageImpl<>(List.of(new PopularRow(12L, "코딩테스트 스터디", 5, 10, "RECRUITING", 3L)), PAGEABLE, 1);
        givenPopular(popularRows);

        // when
        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        assertThat(response.mode()).isEqualTo(RecommendationMode.POPULAR);
        assertThat(response.isProfileRequired()).isFalse();
        assertThat(response.content()).hasSize(1);
    }

    @Test
    @DisplayName("POPULAR 모드는 점수 없이 최근 활동 근거를 붙여 반환한다")
    void getRecommendations_popularRows_returnsPopularItems() {
        // given
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID))).willReturn(List.of());
        Page<PopularStudyRow> rows
            = new PageImpl<>(List.of(new PopularRow(12L, "코딩테스트 스터디", 5, 10, "RECRUITING", 3L)), PAGEABLE, 1);
        givenPopular(rows);

        // when
        StudyRecommendationPageResponse response = service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        assertThat(response.mode()).isEqualTo(RecommendationMode.POPULAR);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
        StudyRecommendationResponse item = response.content().get(0);
        assertThat(item.studyId()).isEqualTo("12");
        assertThat(item.score()).isNull();
        assertThat(item.reasons()).containsExactly("최근 활동이 많은 스터디");
        assertThat(item.status()).isEqualTo(StudyStatus.RECRUITING);
    }

    @Test
    @DisplayName("활동 집계 시작 시각은 현재 시각에서 7일 전으로 조회한다")
    void getRecommendations_activityWindow_sevenDaysBeforeNow() {
        // given
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID))).willReturn(List.of());
        givenPopular(Page.empty(PAGEABLE));

        // when
        service.getRecommendations(MEMBER_ID, PAGEABLE);

        // then
        then(studyRecommendationQueryRepository)
            .should()
            .findPopularStudies(MEMBER_ID, NOW_LOCAL.minusDays(7), PAGEABLE);
    }

    private void givenProfile(boolean hasProfileInput) {
        MemberLearningProfileReplica replica = mock(MemberLearningProfileReplica.class);
        given(replica.hasProfileInput()).willReturn(hasProfileInput);
        given(memberLearningProfileReplicaRepository.findAllByMemberIdIn(List.of(MEMBER_ID)))
            .willReturn(List.of(replica));
    }

    private void givenGeneratedAt(LocalDateTime generatedAt) {
        given(studyRecommendationRepository.findLatestGeneratedAt(MEMBER_ID)).willReturn(Optional.of(generatedAt));
    }

    private void givenPersonalized(Page<PersonalizedStudyRow> rows) {
        given(studyRecommendationQueryRepository.findPersonalizedStudies(MEMBER_ID, PAGEABLE)).willReturn(rows);
    }

    private void givenPopular(Page<PopularStudyRow> rows) {
        given(studyRecommendationQueryRepository.findPopularStudies(eq(MEMBER_ID), any(), eq(PAGEABLE)))
            .willReturn(rows);
    }

    private Page<PersonalizedStudyRow> personalizedPage() {
        PersonalizedStudyRow row
            = new PersonalizedRow(
                12L,
                "코딩테스트 스터디",
                new BigDecimal("87.00"),
                "[\"목표 일치\", \"태그 레벨 비슷함\", \"선호 언어 같음\"]",
                5,
                10,
                "RECRUITING"
            );
        return new PageImpl<>(List.of(row), PAGEABLE, 1);
    }

    private record PersonalizedRow(
        Long studyId,
        String name,
        BigDecimal score,
        String reasonsJson,
        Integer memberCount,
        Integer capacity,
        String status
    ) implements PersonalizedStudyRow {

        @Override
        public Long getStudyId() {
            return studyId;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public BigDecimal getScore() {
            return score;
        }

        @Override
        public String getReasonsJson() {
            return reasonsJson;
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
    }

    private record PopularRow(
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
