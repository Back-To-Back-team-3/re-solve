package com.backtoback.member.member.service;

import java.util.List;

/**
 * {@code LearningProfileUpdated} v1 업무 필드 (이벤트 계약 M4). 공통 헤더는 {@code OutboxEventWriter}가 붙인다.
 */
public record LearningProfileUpdatedPayload(
    String memberId,
    long profileVersion,
    String goal,
    List<String> preferredLanguages,
    List<String> activeTimeSlots,
    List<TagLevelItem> tagLevels
) {

    static LearningProfileUpdatedPayload from(LearningProfileSnapshot snapshot) {
        return new LearningProfileUpdatedPayload(
            String.valueOf(snapshot.memberId()),
            snapshot.profileVersion(),
            snapshot.goal() == null ? null : snapshot.goal().name(),
            snapshot.preferredLanguages(),
            snapshot.activeTimeSlots(),
            snapshot
                .tagLevels()
                .stream()
                .map(tagLevel -> new TagLevelItem(String.valueOf(tagLevel.getTagId()), tagLevel.getLevel().name()))
                .toList()
        );
    }

    public record TagLevelItem(String tagId, String level) {
    }
}
