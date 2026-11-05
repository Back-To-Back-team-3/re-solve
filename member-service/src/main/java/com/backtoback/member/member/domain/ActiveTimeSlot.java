package com.backtoback.member.member.domain;

/**
 * 활동 시간대 (API 명세서 1.3). 스터디의 {@code activeTimeSlot}과 같은 목록이다.
 * MORNING 06–12시, AFTERNOON 12–18시, EVENING 18–24시, NIGHT 00–06시.
 */
public enum ActiveTimeSlot {
    MORNING, AFTERNOON, EVENING, NIGHT
}
