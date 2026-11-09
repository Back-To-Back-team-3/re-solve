package com.backtoback.member.skill.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.skill.store.DiagnosisDraftStore.Draft;
import com.backtoback.member.skill.store.DiagnosisDraftStore.SaveResult;

/**
 * 진단 Draft 저장 응답 (API 명세서 1.5.4). 늦게 도착한 이전 Draft는 {@code applied=false}와 서버의 {@code seq}를 준다.
 */
public record DiagnosisDraftResponse(boolean applied, String seq, OffsetDateTime savedAt) {

    public static DiagnosisDraftResponse from(SaveResult result) {
        Draft draft = result.draft();
        return new DiagnosisDraftResponse(
            result.applied(),
            String.valueOf(draft.seq()),
            draft.savedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}
