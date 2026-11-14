package com.backtoback.notification.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationTest {

    @Test
    @DisplayName("알림 생성 시 기본 읽음 상태는 false이며 readAt은 null이다")
    void createNotification() {
        Notification notification
            = Notification
                .builder()
                .memberId(1L)
                .sourceEventId("evt-123")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("1001번 문제 채점이 완료되었습니다. (결과: AC)")
                .linkUrl("/submissions/1")
                .build();

        assertThat(notification.getMemberId()).isEqualTo(1L);
        assertThat(notification.getSourceEventId()).isEqualTo("evt-123");
        assertThat(notification.getType()).isEqualTo(NotificationType.SUBMISSION_JUDGED);
        assertThat(notification.getTitle()).isEqualTo("채점 완료");
        assertThat(notification.getContent()).isEqualTo("1001번 문제 채점이 완료되었습니다. (결과: AC)");
        assertThat(notification.getLinkUrl()).isEqualTo("/submissions/1");
        assertThat(notification.isRead()).isFalse();
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    @DisplayName("알림 읽음 처리 시 isRead는 true가 되고 readAt이 기록된다")
    void markAsRead() {
        Notification notification
            = Notification
                .builder()
                .memberId(1L)
                .sourceEventId("evt-123")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("채점 완료")
                .build();

        LocalDateTime now = LocalDateTime.now();
        notification.markAsRead(now);

        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("이미 읽은 알림을 다시 읽음 처리해도 최초 readAt 시각이 유지된다")
    void markAsReadIdempotent() {
        Notification notification
            = Notification
                .builder()
                .memberId(1L)
                .sourceEventId("evt-123")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("채점 완료")
                .build();

        LocalDateTime firstReadAt = LocalDateTime.now().minusMinutes(5);
        notification.markAsRead(firstReadAt);

        LocalDateTime secondReadAt = LocalDateTime.now();
        notification.markAsRead(secondReadAt);

        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("읽음 시각(readAt)이 null이면 예외가 발생하고 읽음 상태가 변경되지 않는다")
    void markAsReadWithNullThrowsException() {
        Notification notification
            = Notification
                .builder()
                .memberId(1L)
                .sourceEventId("evt-123")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("채점 완료")
                .build();

        assertThatThrownBy(() -> notification.markAsRead(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("readAt은 null일 수 없습니다.");

        assertThat(notification.isRead()).isFalse();
        assertThat(notification.getReadAt()).isNull();
    }
}
