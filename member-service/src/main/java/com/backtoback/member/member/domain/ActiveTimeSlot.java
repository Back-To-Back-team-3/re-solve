package com.backtoback.member.member.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 활동 시간대 (API 명세서 1.3). 스터디의 {@code activeTimeSlot}과 같은 목록이다.
 * MORNING 06–12시, AFTERNOON 12–18시, EVENING 18–24시, NIGHT 00–06시.
 */
public enum ActiveTimeSlot {
    MORNING, AFTERNOON, EVENING, NIGHT;

    // 기본 Jackson은 숫자를 순서 번호로 읽으므로(0 → 첫 값) 문자열 이름만 받는다. 목록에 없으면 400이 된다.
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static ActiveTimeSlot fromName(String name) {
        return valueOf(name);
    }
}
