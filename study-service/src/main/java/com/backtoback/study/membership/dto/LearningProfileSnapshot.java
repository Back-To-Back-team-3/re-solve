package com.backtoback.study.membership.dto;

import java.util.List;
import java.util.Map;

/**
 * 승인 심사용 학습 프로필 공개 항목 스냅샷(API 명세서 §5.2.4, ERD §4.5.10).
 * JSON 컬럼은 서비스에서 파싱해 전달하며, 복제본이 없으면 {@code null}로 대체한다.
 */
public record LearningProfileSnapshot(
    String goal,
    Map<String, String> tagLevels,
    List<String> preferredLanguages,
    Object activeTimeSlot
) {

}
