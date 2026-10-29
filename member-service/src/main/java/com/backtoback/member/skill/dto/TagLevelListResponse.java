package com.backtoback.member.skill.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.skill.config.SkillProperties.Promotion;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.service.TagLevelSummary;

/**
 * 태그 레벨 목록 응답 (API 명세서 1.4.1). 페이지네이션 없이 진입한 태그만 담는다.
 */
public record TagLevelListResponse(List<TagLevelItem> content) {

    public static TagLevelListResponse from(List<TagLevelSummary> summaries) {
        return new TagLevelListResponse(summaries.stream().map(TagLevelItem::from).toList());
    }

    public record TagLevelItem(
        String tagId,
        String level,
        int accessibleMaxDifficulty,
        List<Achievement> achievements,
        Diagnosis diagnosis,
        NextPromotion nextPromotion
    ) {

        static TagLevelItem from(TagLevelSummary summary) {
            MemberTagLevel tagLevel = summary.tagLevel();
            return new TagLevelItem(
                String.valueOf(tagLevel.getTagId()),
                tagLevel.getLevel().name(),
                tagLevel.getLevel().getAccessibleMaxDifficulty(),
                // shortcut: 성취도 집계(member_tag_achievements) 전이라 빈 배열, 연습 결과 복제본 반영을 구현할 때 채운다.
                List.of(),
                new Diagnosis(summary.diagnosisEligible(), toOffset(summary.cooldownEndsAt()), null),
                NextPromotion.from(summary.nextPromotion())
            );
        }

        private static OffsetDateTime toOffset(LocalDateTime dateTime) {
            return dateTime == null ? null : dateTime.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
        }
    }

    public record Achievement(int difficulty, int solvedCount, BigDecimal achievementRate, String band) {
    }

    /**
     * @param inProgressDiagnosisId 진행 중인 진단 ID. 진단(1.5) 구현 전까지 항상 {@code null}
     */
    public record Diagnosis(boolean isEligible, OffsetDateTime cooldownEndsAt, String inProgressDiagnosisId) {
    }

    public record NextPromotion(String targetLevel, int requiredSolvedCount, BigDecimal requiredAchievementRate) {

        static NextPromotion from(Promotion promotion) {
            if (promotion == null) {
                return null;
            }
            return new NextPromotion(
                promotion.targetLevel().name(),
                promotion.requiredSolvedCount(),
                promotion.requiredAchievementRate()
            );
        }
    }
}
