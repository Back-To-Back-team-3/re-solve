package com.backtoback.study.recommendation.domain;

import java.util.OptionalInt;

/**
 * 회원 태그 레벨과 스터디 실력 수준을 스터디 실력 수준 범위({@code LV1}~{@code LV3})의 순위 1~3으로 바꾼다.
 * 회원 레벨 {@code LV0}·{@code MASTER}는 스터디 구형 데이터와 같은 규칙으로 정규화한다(정책 및 상태 7.1 허용 수준).
 */
public final class SkillLevelRank {

    private SkillLevelRank() {}

    /**
     * 복제본 값은 검사하지 않고 저장하므로 목록 밖 값은 예외 대신 빈 값으로 돌려준다(이벤트 계약서 M4).
     *
     * @return 순위 1~3, 목록 밖 값이나 {@code null}이면 빈 값
     */
    public static OptionalInt of(String level) {
        if (level == null) {
            return OptionalInt.empty();
        }
        return switch (level) {
            case "LV0", "LV1" -> OptionalInt.of(1);
            case "LV2" -> OptionalInt.of(2);
            case "LV3", "MASTER" -> OptionalInt.of(3);
            default -> OptionalInt.empty();
        };
    }
}
