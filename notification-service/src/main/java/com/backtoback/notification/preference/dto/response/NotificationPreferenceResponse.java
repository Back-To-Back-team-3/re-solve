package com.backtoback.notification.preference.dto.response;

import com.backtoback.notification.preference.domain.NotificationPreference;

/**
 * 알림 수신 설정 조회 및 변경 응답 DTO (API 명세서 7.1.6).
 *
 * @param isExamContestEnabled 시험·대회 알림 수신 여부
 * @param isStudyApplicationEnabled 스터디 가입 승인·거절 알림 수신 여부
 * @param isAssignmentEnabled 문제집 배정·마감 임박 알림 수신 여부
 * @param isLevelChangedEnabled 태그 레벨 변경·쿨타임 해제 알림 수신 여부
 * @param isHintReadyEnabled AI 힌트 준비 완료 알림 수신 여부
 * @param isGithubSyncEnabled GitHub 동기화 실패 알림 수신 여부
 */
public record NotificationPreferenceResponse(
    boolean isExamContestEnabled,
    boolean isStudyApplicationEnabled,
    boolean isAssignmentEnabled,
    boolean isLevelChangedEnabled,
    boolean isHintReadyEnabled,
    boolean isGithubSyncEnabled
) {

    /**
     * {@link NotificationPreference} 엔티티를 응답 DTO로 변환한다.
     *
     * @param preference 알림 수신 설정 엔티티
     * @return 알림 수신 설정 응답 DTO
     */
    public static NotificationPreferenceResponse from(NotificationPreference preference) {
        return new NotificationPreferenceResponse(
            preference.isExamContestEnabled(),
            preference.isStudyApplicationEnabled(),
            preference.isAssignmentEnabled(),
            preference.isLevelChangedEnabled(),
            preference.isHintReadyEnabled(),
            preference.isGithubSyncEnabled()
        );
    }

    /**
     * 설정 행이 존재하지 않을 때 반환할 기본 설정(모두 {@code true}) 응답을 생성한다 (API 명세서 7.1.6).
     *
     * @return 모든 항목이 {@code true}인 기본 응답 DTO
     */
    public static NotificationPreferenceResponse defaultPreference() {
        return new NotificationPreferenceResponse(true, true, true, true, true, true);
    }
}
