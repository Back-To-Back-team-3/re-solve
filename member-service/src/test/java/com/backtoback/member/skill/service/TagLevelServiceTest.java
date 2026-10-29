package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.service.MemberService;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.config.SkillProperties;
import com.backtoback.member.skill.config.SkillProperties.Promotion;
import com.backtoback.member.skill.domain.LevelChangeReason;
import com.backtoback.member.skill.domain.MemberLevelHistory;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.domain.TagLevel;
import com.backtoback.member.skill.repository.MemberLevelHistoryRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;

@ExtendWith(MockitoExtension.class)
class TagLevelServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final Promotion LV1_TO_LV2 = new Promotion(TagLevel.LV2, 5, new BigDecimal("60.00"));

    @Mock
    private MemberTagLevelRepository tagLevelRepository;

    @Mock
    private MemberLevelHistoryRepository historyRepository;

    @Mock
    private MemberService memberService;

    @Mock
    private ProblemTagClient problemTagClient;

    private TagLevelService tagLevelService;

    @BeforeEach
    void setUp() {
        SkillProperties properties
            = new SkillProperties("http://problem-service", Duration.ofDays(3), Map.of(TagLevel.LV1, LV1_TO_LV2));
        Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        tagLevelService
            = new TagLevelService(
                tagLevelRepository,
                historyRepository,
                memberService,
                problemTagClient,
                properties,
                clock
            );
    }

    @Test
    @DisplayName("태그 진입: LV1로 저장하고 ENTRY_SKIP 이력을 남긴다")
    void enterStartsAtLv1WithHistory() {
        given(memberService.getMember(7L)).willReturn(member(MemberStatus.ACTIVE));
        given(problemTagClient.exists(3L)).willReturn(true);

        MemberTagLevel tagLevel = tagLevelService.enter(7L, 3L);

        assertThat(tagLevel.getMemberId()).isEqualTo(7L);
        assertThat(tagLevel.getTagId()).isEqualTo(3L);
        assertThat(tagLevel.getLevel()).isEqualTo(TagLevel.LV1);
        assertThat(tagLevel.getEnteredAt()).isEqualTo(NOW);
        assertThat(tagLevel.getLastDiagnosisEndedAt()).isNull();
        ArgumentCaptor<MemberLevelHistory> history = ArgumentCaptor.forClass(MemberLevelHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getFromLevel()).isNull();
        assertThat(history.getValue().getToLevel()).isEqualTo(TagLevel.LV1);
        assertThat(history.getValue().getReason()).isEqualTo(LevelChangeReason.ENTRY_SKIP);
    }

    @Test
    @DisplayName("태그 진입: 이미 진입했거나 동시 진입이 유일 제약에 걸리면 LEVEL_ALREADY_ENTERED")
    void enterRejectsDuplicate() {
        given(memberService.getMember(7L)).willReturn(member(MemberStatus.ACTIVE));
        given(tagLevelRepository.existsByMemberIdAndTagId(7L, 3L)).willReturn(true, false);
        given(problemTagClient.exists(3L)).willReturn(true);
        given(tagLevelRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk"));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> tagLevelService.enter(7L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.LEVEL_ALREADY_ENTERED);
        }
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("태그 진입: problem에 없는 태그면 LEVEL_TAG_NOT_FOUND, 정지 회원이면 MEMBER_SUSPENDED")
    void enterRejectsMissingTagAndSuspendedMember() {
        given(memberService.getMember(7L)).willReturn(member(MemberStatus.ACTIVE));
        given(problemTagClient.exists(99L)).willReturn(false);
        assertThatThrownBy(() -> tagLevelService.enter(7L, 99L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.LEVEL_TAG_NOT_FOUND);

        given(memberService.getMember(8L)).willReturn(member(MemberStatus.SUSPENDED));
        assertThatThrownBy(() -> tagLevelService.enter(8L, 3L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.MEMBER_SUSPENDED);
        verify(tagLevelRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("레벨 목록: 쿨타임 중이면 응시 불가와 종료 시각을, 끝났거나 진단 기록이 없으면 응시 가능을 준다")
    void getTagLevelsComputesDiagnosisCooldown() {
        MemberTagLevel cooling = tagLevel(1L, TagLevel.LV1, NOW.minusDays(1));
        MemberTagLevel cooled = tagLevel(2L, TagLevel.LV1, NOW.minusDays(3));
        MemberTagLevel fresh = tagLevel(3L, TagLevel.LV1, null);
        given(tagLevelRepository.findAllByMemberIdOrderByTagIdAsc(7L)).willReturn(List.of(cooling, cooled, fresh));

        List<TagLevelSummary> summaries = tagLevelService.getTagLevels(7L);

        assertThat(summaries.get(0).diagnosisEligible()).isFalse();
        assertThat(summaries.get(0).cooldownEndsAt()).isEqualTo(NOW.plusDays(2));
        assertThat(summaries.get(1).diagnosisEligible()).isTrue();
        assertThat(summaries.get(1).cooldownEndsAt()).isNull();
        assertThat(summaries.get(2).diagnosisEligible()).isTrue();
        assertThat(summaries).allSatisfy(summary -> assertThat(summary.nextPromotion()).isEqualTo(LV1_TO_LV2));
    }

    @Test
    @DisplayName("레벨 목록: LV3·MASTER는 진단 응시 대상이 아니고, 설정에 없는 레벨은 다음 승급 기준이 없다")
    void getTagLevelsExcludesTopLevels() {
        given(tagLevelRepository.findAllByMemberIdOrderByTagIdAsc(7L))
            .willReturn(List.of(tagLevel(1L, TagLevel.LV3, null), tagLevel(2L, TagLevel.MASTER, null)));

        List<TagLevelSummary> summaries = tagLevelService.getTagLevels(7L);

        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.diagnosisEligible()).isFalse();
            assertThat(summary.nextPromotion()).isNull();
        });
        verifyNoInteractions(problemTagClient);
    }

    private Member member(MemberStatus status) {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }

    private MemberTagLevel tagLevel(Long tagId, TagLevel level, LocalDateTime lastDiagnosisEndedAt) {
        MemberTagLevel tagLevel = MemberTagLevel.enterWithoutDiagnosis(7L, tagId, NOW.minusDays(10));
        ReflectionTestUtils.setField(tagLevel, "level", level);
        ReflectionTestUtils.setField(tagLevel, "lastDiagnosisEndedAt", lastDiagnosisEndedAt);
        return tagLevel;
    }
}
