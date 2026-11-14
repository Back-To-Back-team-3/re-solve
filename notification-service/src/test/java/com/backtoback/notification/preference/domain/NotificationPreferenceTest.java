package com.backtoback.notification.preference.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationPreferenceTest {

    @Test
    @DisplayName("기본 설정 생성 시 모든 알림 수신 설정은 true이다")
    void createDefault() {
        NotificationPreference preference = NotificationPreference.createDefault(1L);

        assertThat(preference.getMemberId()).isEqualTo(1L);
        assertThat(preference.isExamContestEnabled()).isTrue();
        assertThat(preference.isStudyApplicationEnabled()).isTrue();
        assertThat(preference.isAssignmentEnabled()).isTrue();
        assertThat(preference.isLevelChangedEnabled()).isTrue();
        assertThat(preference.isHintReadyEnabled()).isTrue();
        assertThat(preference.isGithubSyncEnabled()).isTrue();
    }

    @Test
    @DisplayName("수신 설정 변경 시 지정한 필드만 변경되고 null인 필드는 기존 값을 유지한다")
    void updatePreferences() {
        NotificationPreference preference = NotificationPreference.createDefault(1L);

        preference.update(false, null, false, null, null, false);

        assertThat(preference.isExamContestEnabled()).isFalse();
        assertThat(preference.isStudyApplicationEnabled()).isTrue(); // 기존 유지
        assertThat(preference.isAssignmentEnabled()).isFalse();
        assertThat(preference.isLevelChangedEnabled()).isTrue(); // 기존 유지
        assertThat(preference.isHintReadyEnabled()).isTrue(); // 기존 유지
        assertThat(preference.isGithubSyncEnabled()).isFalse();
    }
}
