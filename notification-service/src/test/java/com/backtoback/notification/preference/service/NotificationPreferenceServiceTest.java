package com.backtoback.notification.preference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.backtoback.notification.preference.domain.NotificationPreference;
import com.backtoback.notification.preference.dto.request.NotificationPreferenceUpdateRequest;
import com.backtoback.notification.preference.dto.response.NotificationPreferenceResponse;
import com.backtoback.notification.preference.repository.NotificationPreferenceRepository;

class NotificationPreferenceServiceTest {

    private NotificationPreferenceRepository preferenceRepository;
    private NotificationPreferenceService preferenceService;

    @BeforeEach
    void setUp() {
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        preferenceService = new NotificationPreferenceService(preferenceRepository);
    }

    @Test
    @DisplayName("7.1.6 수신 설정 행이 없을 때 조회하면 DB 저장 없이 모든 항목이 true인 기본 설정을 반환한다")
    void getPreferencesWhenNotExistsReturnsDefaultTrue() {
        // given
        Long memberId = 1L;
        given(preferenceRepository.findByMemberId(memberId)).willReturn(Optional.empty());

        // when
        NotificationPreferenceResponse response = preferenceService.getPreferences(memberId);

        // then
        assertThat(response.isExamContestEnabled()).isTrue();
        assertThat(response.isStudyApplicationEnabled()).isTrue();
        assertThat(response.isAssignmentEnabled()).isTrue();
        assertThat(response.isLevelChangedEnabled()).isTrue();
        assertThat(response.isHintReadyEnabled()).isTrue();
        assertThat(response.isGithubSyncEnabled()).isTrue();
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("7.1.6 수신 설정 행이 존재할 때 조회하면 저장된 설정값을 그대로 반환한다")
    void getPreferencesWhenExistsReturnsSavedPreference() {
        // given
        Long memberId = 1L;
        NotificationPreference saved = new NotificationPreference(memberId, true, false, false, true, false, true);
        given(preferenceRepository.findByMemberId(memberId)).willReturn(Optional.of(saved));

        // when
        NotificationPreferenceResponse response = preferenceService.getPreferences(memberId);

        // then
        assertThat(response.isExamContestEnabled()).isTrue();
        assertThat(response.isStudyApplicationEnabled()).isFalse();
        assertThat(response.isAssignmentEnabled()).isFalse();
        assertThat(response.isLevelChangedEnabled()).isTrue();
        assertThat(response.isHintReadyEnabled()).isFalse();
        assertThat(response.isGithubSyncEnabled()).isTrue();
    }

    @Test
    @DisplayName("7.1.6 최초 변경 요청 시 기본값(모두 true)으로 행을 생성한 뒤 요청 필드만 부분 갱신하여 저장한다")
    void updatePreferencesWhenNotExistsCreatesDefaultAndUpdatesPartially() {
        // given
        Long memberId = 2L;
        given(preferenceRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(preferenceRepository.save(any(NotificationPreference.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        NotificationPreferenceUpdateRequest request
            = new NotificationPreferenceUpdateRequest(null, null, false, null, null, null);

        // when
        NotificationPreferenceResponse response = preferenceService.updatePreferences(memberId, request);

        // then
        assertThat(response.isExamContestEnabled()).isTrue();
        assertThat(response.isStudyApplicationEnabled()).isTrue();
        assertThat(response.isAssignmentEnabled()).isFalse();
        assertThat(response.isLevelChangedEnabled()).isTrue();
        assertThat(response.isHintReadyEnabled()).isTrue();
        assertThat(response.isGithubSyncEnabled()).isTrue();

        ArgumentCaptor<NotificationPreference> captor = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferenceRepository).save(captor.capture());
        assertThat(captor.getValue().getMemberId()).isEqualTo(memberId);
        assertThat(captor.getValue().isAssignmentEnabled()).isFalse();
        assertThat(captor.getValue().isExamContestEnabled()).isTrue();
    }

    @Test
    @DisplayName("7.1.6 기존 설정 행이 있을 때 변경 요청하면 null이 아닌 필드만 갱신하고 나머지는 유지한다")
    void updatePreferencesWhenExistsUpdatesOnlyNonNullFields() {
        // given
        Long memberId = 3L;
        NotificationPreference existing = new NotificationPreference(memberId, false, true, true, true, false, true);
        given(preferenceRepository.findByMemberId(memberId)).willReturn(Optional.of(existing));
        given(preferenceRepository.save(existing)).willReturn(existing);

        NotificationPreferenceUpdateRequest request
            = new NotificationPreferenceUpdateRequest(true, null, false, null, null, false);

        // when
        NotificationPreferenceResponse response = preferenceService.updatePreferences(memberId, request);

        // then
        assertThat(response.isExamContestEnabled()).isTrue();
        assertThat(response.isStudyApplicationEnabled()).isTrue();
        assertThat(response.isAssignmentEnabled()).isFalse();
        assertThat(response.isLevelChangedEnabled()).isTrue();
        assertThat(response.isHintReadyEnabled()).isFalse();
        assertThat(response.isGithubSyncEnabled()).isFalse();
    }
}
