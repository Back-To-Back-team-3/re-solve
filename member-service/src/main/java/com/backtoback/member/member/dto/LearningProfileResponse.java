package com.backtoback.member.member.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.member.service.LearningProfileSnapshot;

/**
 * 학습 프로필 조회 응답 (API 명세서 1.3.1). 태그 이름은 담지 않는다(1.4.1과 같음, 이름은 problem 태그 목록에서 붙인다).
 */
public record LearningProfileResponse(
    String goal,
    List<String> preferredLanguages,
    List<String> activeTimeSlots,
    List<TagLevelItem> tagLevels,
    String profileVersion,
    OffsetDateTime updatedAt
) {

    public static LearningProfileResponse from(LearningProfileSnapshot snapshot) {
        return new LearningProfileResponse(
            snapshot.goal().name(),
            snapshot.preferredLanguages(),
            snapshot.activeTimeSlots(),
            snapshot
                .tagLevels()
                .stream()
                .map(tagLevel -> new TagLevelItem(String.valueOf(tagLevel.getTagId()), tagLevel.getLevel().name()))
                .toList(),
            String.valueOf(snapshot.profileVersion()),
            snapshot.updatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }

    public record TagLevelItem(String tagId, String level) {
    }
}
