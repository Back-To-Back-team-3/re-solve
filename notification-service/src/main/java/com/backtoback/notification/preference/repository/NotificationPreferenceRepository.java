package com.backtoback.notification.preference.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.notification.preference.domain.NotificationPreference;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    Optional<NotificationPreference> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);
}
