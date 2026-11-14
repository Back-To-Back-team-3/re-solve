package com.backtoback.member.member.service;

/**
 * {@code TagLevelChanged} v1 업무 필드 (이벤트 계약 M5). notification이 레벨 알림을 만든다.
 *
 * @param tagName 알림 문구용 스냅샷
 * @param fromLevel 첫 진입(진단 결과)이면 {@code LV0}
 * @param reasonType {@code DIAGNOSIS} / {@code PROMOTION} / {@code REJUDGE} / {@code COOLDOWN_RELEASED}
 * @param diagnosisId {@code DIAGNOSIS}·{@code COOLDOWN_RELEASED}만
 * @param basisSubmissionId {@code PROMOTION}·{@code REJUDGE}일 때 근거 제출
 */
public record TagLevelChangedPayload(
    String memberId,
    String tagId,
    String tagName,
    String fromLevel,
    String toLevel,
    String reasonType,
    String diagnosisId,
    String basisSubmissionId
) {

}
