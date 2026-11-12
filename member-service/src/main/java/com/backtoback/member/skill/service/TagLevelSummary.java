package com.backtoback.member.skill.service;

import java.time.LocalDateTime;
import java.util.List;

import com.backtoback.member.skill.config.SkillProperties.Promotion;
import com.backtoback.member.skill.domain.MemberTagLevel;

/**
 * 태그 레벨 목록(API 명세서 1.4.1) 한 항목의 계산 결과.
 *
 * @param cooldownEndsAt 재응시 쿨타임이 끝나는 시각. 쿨타임 중이 아니면 {@code null}
 * @param nextPromotion 다음 승급 기준. 더 오를 레벨이 없거나 미시작이면 {@code null}
 * @param inProgressDiagnosisId 이 태그의 진행 중 진단 ID. 없으면 {@code null}
 * @param achievements 이 태그의 난이도별 성취도 (난이도 순, 채점 완료 연습 제출이 있는 난이도만)
 */
public record TagLevelSummary(
    MemberTagLevel tagLevel,
    boolean diagnosisEligible,
    LocalDateTime cooldownEndsAt,
    Promotion nextPromotion,
    Long inProgressDiagnosisId,
    List<TagAchievement> achievements
) {

}
