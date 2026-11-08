package com.backtoback.study.operation.service;

import java.time.OffsetDateTime;

/**
 * StudyClosed 이벤트 페이로드 (이벤트 계약서 §S4).
 *
 * @param studyId 종료된 스터디 ID
 * @param sourceVersion 종료 후 studies.version
 * @param closedAt 종료 시각 (+09:00 오프셋 포함)
 */
public record StudyClosedPayload(Long studyId, Long sourceVersion, OffsetDateTime closedAt) {

}
