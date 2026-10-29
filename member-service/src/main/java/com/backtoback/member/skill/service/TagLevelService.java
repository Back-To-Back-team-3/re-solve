package com.backtoback.member.skill.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.service.MemberService;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.config.SkillProperties;
import com.backtoback.member.skill.domain.MemberLevelHistory;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.repository.MemberLevelHistoryRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TagLevelService {

    private final MemberTagLevelRepository tagLevelRepository;
    private final MemberLevelHistoryRepository historyRepository;
    private final MemberService memberService;
    private final ProblemTagClient problemTagClient;
    private final SkillProperties properties;
    private final Clock clock;

    /**
     * 태그에 진단 없이 진입한다 (API 명세서 1.4.2). {@code LV1}로 시작하고 변경 이력을 남긴다.
     * 같은 태그의 동시 진입은 {@code uk_member_tag_levels_member_tag}가 막고, 위반 시 {@code LEVEL_ALREADY_ENTERED}로 응답한다.
     */
    @Transactional
    public MemberTagLevel enter(Long memberId, Long tagId) {
        Member member = memberService.getMember(memberId);
        if (member.isSuspended()) {
            throw new MemberException(MemberErrorCode.MEMBER_SUSPENDED);
        }
        if (tagLevelRepository.existsByMemberIdAndTagId(memberId, tagId)) {
            throw new MemberException(MemberErrorCode.LEVEL_ALREADY_ENTERED);
        }
        if (!problemTagClient.exists(tagId)) {
            throw new MemberException(MemberErrorCode.LEVEL_TAG_NOT_FOUND);
        }

        MemberTagLevel tagLevel = MemberTagLevel.enterWithoutDiagnosis(memberId, tagId, LocalDateTime.now(clock));
        try {
            tagLevelRepository.saveAndFlush(tagLevel);
        } catch (DataIntegrityViolationException exception) {
            throw new MemberException(MemberErrorCode.LEVEL_ALREADY_ENTERED, exception);
        }
        historyRepository.save(MemberLevelHistory.entrySkip(tagLevel));
        // shortcut: learning_profiles.profile_version 증가·LearningProfileUpdated 발행 없음, 학습 프로필(1.3)과 Outbox를 적용할 때 같은
        // 트랜잭션에서 처리한다.
        return tagLevel;
    }

    /**
     * 진입한 태그의 레벨과 진단 응시 가능 여부·다음 승급 기준을 계산한다 (API 명세서 1.4.1).
     */
    @Transactional(readOnly = true)
    public List<TagLevelSummary> getTagLevels(Long memberId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return tagLevelRepository.findAllByMemberIdOrderByTagIdAsc(memberId).stream().map(tagLevel -> {
            LocalDateTime cooldownEndsAt = cooldownEndsAt(tagLevel, now);
            // shortcut: 진행 중인 진단 확인 없음, 진단(1.5) 구현 시 같은 태그의 진행 중 진단이 있으면 응시 불가로 바꾼다.
            boolean eligible = tagLevel.getLevel().isDiagnosable() && cooldownEndsAt == null;
            return new TagLevelSummary(
                tagLevel,
                eligible,
                cooldownEndsAt,
                properties.promotions().get(tagLevel.getLevel())
            );
        }).toList();
    }

    /**
     * 진입한 태그의 레벨만 조회한다 (API 명세서 1.7.2, judge의 연습 제출 레벨 확인용). 진입하지 않은 태그는 담지 않는다.
     */
    @Transactional(readOnly = true)
    public List<MemberTagLevel> getEnteredTagLevels(Long memberId) {
        return tagLevelRepository.findAllByMemberIdOrderByTagIdAsc(memberId);
    }

    private LocalDateTime cooldownEndsAt(MemberTagLevel tagLevel, LocalDateTime now) {
        if (tagLevel.getLastDiagnosisEndedAt() == null) {
            return null;
        }
        LocalDateTime endsAt = tagLevel.getLastDiagnosisEndedAt().plus(properties.diagnosisCooldown());
        return now.isBefore(endsAt) ? endsAt : null;
    }
}
