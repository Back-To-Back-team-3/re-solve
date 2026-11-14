package com.backtoback.member.skill.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.skill.config.SkillProperties.Promotion;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.service.TagAchievement;
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
                summary.achievements().stream().map(Achievement::from).toList(),
                new Diagnosis(
                    summary.diagnosisEligible(),
                    toOffset(summary.cooldownEndsAt()),
                    summary.inProgressDiagnosisId() == null ? null : String.valueOf(summary.inProgressDiagnosisId())
                ),
                NextPromotion.from(summary.nextPromotion())
            );
        }

        private static OffsetDateTime toOffset(LocalDateTime dateTime) {
            return dateTime == null ? null : dateTime.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
        }
    }

    public record Achievement(int difficulty, int solvedCount, BigDecimal achievementRate, String band) {

        static Achievement from(TagAchievement achievement) {
            return new Achievement(
                achievement.difficulty(),
                achievement.solvedCount(),
                achievement.achievementRate(),
                achievement.band()
            );
        }
    }

    /**
     * @param inProgressDiagnosisId 이 태그의 진행 중인 진단 ID. 없으면 {@code null}
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
