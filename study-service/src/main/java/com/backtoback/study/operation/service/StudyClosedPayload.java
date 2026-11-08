package com.backtoback.study.operation.service;

import java.time.LocalDateTime;

/**
 * StudyClosed 이벤트 페이로드 (이벤트 계약서 §S4).
 *
 * @param studyId 종료된 스터디 ID
 * @param sourceVersion 종료 후 studies.version
 * @param closedAt 종료 시각
 */
public record StudyClosedPayload(Long studyId, Long sourceVersion, LocalDateTime closedAt) {

}
