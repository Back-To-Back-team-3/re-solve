package com.backtoback.member.member.service;

import java.time.LocalDateTime;
import java.util.List;

import com.backtoback.member.member.domain.LearningGoal;
import com.backtoback.member.skill.domain.MemberTagLevel;

/**
 * 학습 프로필 전체 상태. 조회 응답(1.3.1)과 {@code LearningProfileUpdated} 스냅샷(M4)이 같은 값을 쓴다.
 *
 * @param goal 미입력이면 {@code null}
 * @param preferredLanguages 미입력이면 빈 배열
 * @param activeTimeSlots 미입력이면 빈 배열
 * @param tagLevels 진입한 태그 전체 (태그 ID 순)
 */
public record LearningProfileSnapshot(
    Long memberId,
    LearningGoal goal,
    List<String> preferredLanguages,
    List<String> activeTimeSlots,
    List<MemberTagLevel> tagLevels,
    long profileVersion,
    LocalDateTime updatedAt
) {

}
