package com.backtoback.member.member.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 학습 목표 (API 명세서 1.3). 스터디의 {@code goal}과 같은 목록이다.
 */
public enum LearningGoal {
    EMPLOYMENT, CONTEST, STUDY;

    // 기본 Jackson은 숫자를 순서 번호로 읽으므로(0 → 첫 값) 문자열 이름만 받는다. 목록에 없으면 400이 된다.
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static LearningGoal fromName(String name) {
        return valueOf(name);
    }
}
