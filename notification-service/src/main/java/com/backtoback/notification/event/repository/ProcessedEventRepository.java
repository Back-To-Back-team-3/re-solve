package com.backtoback.notification.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.notification.event.domain.ProcessedEvent;

/**
 * 처리 완료 이벤트 영속성 리포지토리 (ERD 3.2 processed_events).
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventId(String eventId);
}
