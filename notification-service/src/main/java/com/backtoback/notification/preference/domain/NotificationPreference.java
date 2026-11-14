package com.backtoback.notification.preference.domain;

import org.hibernate.annotations.DynamicUpdate;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@DynamicUpdate
@Table(
    name = "notification_preferences",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_notification_preferences_member_id",
            columnNames = {
                "member_id"
            }
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        name = "is_exam_contest_enabled",
        nullable = false
    )
    private boolean isExamContestEnabled;

    @Column(
        name = "is_study_application_enabled",
        nullable = false
    )
    private boolean isStudyApplicationEnabled;

    @Column(
        name = "is_assignment_enabled",
        nullable = false
    )
    private boolean isAssignmentEnabled;

    @Column(
        name = "is_level_changed_enabled",
        nullable = false
    )
    private boolean isLevelChangedEnabled;

    @Column(
        name = "is_hint_ready_enabled",
        nullable = false
    )
    private boolean isHintReadyEnabled;

    @Column(
        name = "is_github_sync_enabled",
        nullable = false
    )
    private boolean isGithubSyncEnabled;

    @Builder
    public NotificationPreference(
        Long memberId,
        boolean isExamContestEnabled,
        boolean isStudyApplicationEnabled,
        boolean isAssignmentEnabled,
        boolean isLevelChangedEnabled,
        boolean isHintReadyEnabled,
        boolean isGithubSyncEnabled
    ) {
        this.memberId = memberId;
        this.isExamContestEnabled = isExamContestEnabled;
        this.isStudyApplicationEnabled = isStudyApplicationEnabled;
        this.isAssignmentEnabled = isAssignmentEnabled;
        this.isLevelChangedEnabled = isLevelChangedEnabled;
        this.isHintReadyEnabled = isHintReadyEnabled;
        this.isGithubSyncEnabled = isGithubSyncEnabled;
    }

    public static NotificationPreference createDefault(Long memberId) {
        return new NotificationPreference(memberId, true, true, true, true, true, true);
    }

    public void update(
        Boolean isExamContestEnabled,
        Boolean isStudyApplicationEnabled,
        Boolean isAssignmentEnabled,
        Boolean isLevelChangedEnabled,
        Boolean isHintReadyEnabled,
        Boolean isGithubSyncEnabled
    ) {
        if (isExamContestEnabled != null) {
            this.isExamContestEnabled = isExamContestEnabled;
        }
        if (isStudyApplicationEnabled != null) {
            this.isStudyApplicationEnabled = isStudyApplicationEnabled;
        }
        if (isAssignmentEnabled != null) {
            this.isAssignmentEnabled = isAssignmentEnabled;
        }
        if (isLevelChangedEnabled != null) {
            this.isLevelChangedEnabled = isLevelChangedEnabled;
        }
        if (isHintReadyEnabled != null) {
            this.isHintReadyEnabled = isHintReadyEnabled;
        }
        if (isGithubSyncEnabled != null) {
            this.isGithubSyncEnabled = isGithubSyncEnabled;
        }
    }
}
