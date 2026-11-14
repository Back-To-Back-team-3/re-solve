package com.backtoback.notification.preference.dto.request;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 알림 수신 설정 부분 변경 요청 DTO (API 명세서 7.1.6).
 * 전달된 필드(non-null)만 갱신하며, 생략된 필드(null)는 기존 설정을 유지한다.
 * 문자열이나 숫자 등의 강제 형변환을 막고 JSON boolean 토큰만 허용한다.
 *
 * @param isExamContestEnabled 시험·대회 알림 수신 여부
 * @param isStudyApplicationEnabled 스터디 가입 승인·거절 알림 수신 여부
 * @param isAssignmentEnabled 문제집 배정·마감 임박 알림 수신 여부
 * @param isLevelChangedEnabled 태그 레벨 변경·쿨타임 해제 알림 수신 여부
 * @param isHintReadyEnabled AI 힌트 준비 완료 알림 수신 여부
 * @param isGithubSyncEnabled GitHub 동기화 실패 알림 수신 여부
 */
public record NotificationPreferenceUpdateRequest(
    @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean isExamContestEnabled,
    @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean isStudyApplicationEnabled,
    @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean isAssignmentEnabled,
    @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean isLevelChangedEnabled,
    @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean isHintReadyEnabled,
    @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean isGithubSyncEnabled
) {
}
