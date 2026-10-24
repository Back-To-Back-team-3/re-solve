package com.backtoback.notification.preference.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.notification.preference.domain.NotificationPreference;
import com.backtoback.notification.preference.dto.request.NotificationPreferenceUpdateRequest;
import com.backtoback.notification.preference.dto.response.NotificationPreferenceResponse;
import com.backtoback.notification.preference.repository.NotificationPreferenceRepository;

import lombok.RequiredArgsConstructor;

/**
 * 회원별 알림 수신 설정 조회 및 부분 변경을 담당하는 서비스 (API 명세서 7.1.6).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;

    /**
     * 회원의 알림 수신 설정을 조회한다 (API 명세서 7.1.6).
     * 설정 행이 존재하지 않으면 DB에 행을 생성하지 않고 모든 항목이 {@code true}인 기본값을 반환한다.
     *
     * @param memberId 회원 ID
     * @return 알림 수신 설정 응답
     */
    public NotificationPreferenceResponse getPreferences(Long memberId) {
        return preferenceRepository
            .findByMemberId(memberId)
            .map(NotificationPreferenceResponse::from)
            .orElseGet(NotificationPreferenceResponse::defaultPreference);
    }

    /**
     * 회원의 알림 수신 설정을 부분 변경한다 (API 명세서 7.1.6).
     * 설정 행이 존재하지 않으면 기본값(모두 {@code true})으로 행을 생성한 뒤 요청에 포함된 필드만 갱신한다.
     *
     * @param memberId 회원 ID
     * @param request 부분 변경할 알림 수신 설정 요청
     * @return 변경 후 알림 수신 설정 응답
     */
    @Transactional
    public NotificationPreferenceResponse updatePreferences(
        Long memberId,
        NotificationPreferenceUpdateRequest request
    ) {
        NotificationPreference preference
            = preferenceRepository
                .findByMemberId(memberId)
                .orElseGet(() -> NotificationPreference.createDefault(memberId));

        preference
            .update(
                request.isExamContestEnabled(),
                request.isStudyApplicationEnabled(),
                request.isAssignmentEnabled(),
                request.isLevelChangedEnabled(),
                request.isHintReadyEnabled(),
                request.isGithubSyncEnabled()
            );

        NotificationPreference saved = preferenceRepository.save(preference);
        return NotificationPreferenceResponse.from(saved);
    }
}
