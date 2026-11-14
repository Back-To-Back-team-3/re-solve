package com.backtoback.member.skill.service;

import java.math.BigDecimal;

/**
 * 회원·태그·난이도별 성취도 (ERD 4.1.4 {@code member_tag_achievements}).
 *
 * @param achievementRate 성취도(%), 소수 둘째 자리
 */
public record TagAchievement(long tagId, int difficulty, int solvedCount, BigDecimal achievementRate) {

    private static final BigDecimal GOOD = new BigDecimal("80");
    private static final BigDecimal NORMAL = new BigDecimal("60");

    /**
     * 성취도 구간 (정책 및 상태 8.3): 잘함 80% 이상 / 보통 60~79% / 부족 0~59%.
     */
    public String band() {
        if (achievementRate.compareTo(GOOD) >= 0) {
            return "GOOD";
        }
        return achievementRate.compareTo(NORMAL) >= 0 ? "NORMAL" : "WEAK";
    }
}
