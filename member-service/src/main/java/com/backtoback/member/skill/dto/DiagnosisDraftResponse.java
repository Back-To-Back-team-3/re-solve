package com.backtoback.member.skill.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.skill.store.DiagnosisDraftStore.Draft;

/**
 * 진단 Draft 저장 응답 (API 명세서 1.5.4). 늦게 도착한 이전 Draft는 {@code applied=false}와 서버의 {@code seq}를 준다.
 */
public record DiagnosisDraftResponse(boolean applied, String seq, OffsetDateTime savedAt) {

    public static DiagnosisDraftResponse of(long requestedSeq, Draft saved) {
        return new DiagnosisDraftResponse(
            saved.seq() == requestedSeq,
            String.valueOf(saved.seq()),
            saved.savedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}
