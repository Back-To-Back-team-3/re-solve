package com.backtoback.member.skill.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 태그 레벨 (ERD 4.1.3). 연습 제출 가능한 최대 난이도와 진단 응시 가능 여부를 함께 가진다.
 */
@Getter
@RequiredArgsConstructor
public enum TagLevel {

    LV0(0, true), LV1(1, true), LV2(2, true), LV3(3, false), MASTER(3, false);

    private final int accessibleMaxDifficulty;
    private final boolean diagnosable;
}
