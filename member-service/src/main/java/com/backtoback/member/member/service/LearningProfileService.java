package com.backtoback.member.member.service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.member.member.domain.ActiveTimeSlot;
import com.backtoback.member.member.domain.LearningGoal;
import com.backtoback.member.member.domain.LearningProfile;
import com.backtoback.member.member.repository.LearningProfileRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class LearningProfileService {

    private final LearningProfileRepository profileRepository;
    private final MemberTagLevelRepository tagLevelRepository;
    private final MemberService memberService;
    private final MemberEventPublisher memberEventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 학습 프로필 조회 (API 명세서 1.3.1). 프로필 항목을 입력하지 않았으면 비어 있다.
     */
    @Transactional(readOnly = true)
    public Optional<LearningProfileSnapshot> getProfile(Long memberId) {
        return profileRepository.findByMemberId(memberId).filter(LearningProfile::isEntered).map(this::snapshot);
    }

    /**
     * 내 정보 조회(1.2.1)의 {@code hasLearningProfile}.
     */
    @Transactional(readOnly = true)
    public boolean hasProfile(Long memberId) {
        return profileRepository.existsByMemberIdAndGoalIsNotNull(memberId);
    }

    /**
     * 학습 프로필 저장 (API 명세서 1.3.2). 배열은 중복을 없애고 정렬해 저장하므로 순서만 바뀐 요청은 같은 값이다.
     * 값이 바뀐 경우에만 순번을 올리고 같은 트랜잭션에서 {@code LearningProfileUpdated}를 기록한다.
     *
     * @return 저장 후 {@code profile_version}
     */
    @Transactional
    public long save(
        Long memberId,
        LearningGoal goal,
        Collection<String> preferredLanguages,
        Collection<ActiveTimeSlot> activeTimeSlots
    ) {
        memberService.lockActiveMember(memberId);
        LearningProfile profile = findOrEmpty(memberId);
        List<String> languages = List.copyOf(new TreeSet<>(preferredLanguages));
        List<String> timeSlots = EnumSet.copyOf(activeTimeSlots).stream().map(Enum::name).toList();
        // MySQL JSON 컬럼은 저장 시 공백 등을 다시 쓰므로 문자열이 아니라 읽은 목록끼리 비교한다.
        if (goal == profile.getGoal() && languages.equals(readList(profile.getPreferredLanguagesJson()))
            && timeSlots.equals(readList(profile.getActiveTimeSlotsJson()))) {
            return profile.getProfileVersion();
        }
        profile.change(goal, objectMapper.writeValueAsString(languages), objectMapper.writeValueAsString(timeSlots));
        publish(profileRepository.saveAndFlush(profile));
        return profile.getProfileVersion();
    }

    /**
     * 태그 레벨이 바뀐 트랜잭션에서 순번을 올리고 {@code LearningProfileUpdated}를 기록한다 (이벤트 계약 M4 ③④).
     * 호출하는 쪽이 {@link MemberService#lockActiveMember}로 회원 행을 먼저 잠가야 순번이 겹치지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void tagLevelsChanged(Long memberId) {
        LearningProfile profile = findOrEmpty(memberId);
        profile.tagLevelsChanged();
        publish(profileRepository.saveAndFlush(profile));
    }

    private LearningProfile findOrEmpty(Long memberId) {
        return profileRepository.findByMemberId(memberId).orElseGet(() -> LearningProfile.empty(memberId));
    }

    private void publish(LearningProfile profile) {
        memberEventPublisher.learningProfileUpdated(snapshot(profile));
    }

    private LearningProfileSnapshot snapshot(LearningProfile profile) {
        return new LearningProfileSnapshot(
            profile.getMemberId(),
            profile.getGoal(),
            readList(profile.getPreferredLanguagesJson()),
            readList(profile.getActiveTimeSlotsJson()),
            tagLevelRepository.findAllByMemberIdOrderByTagIdAsc(profile.getMemberId()),
            profile.getProfileVersion(),
            profile.getUpdatedAt()
        );
    }

    private List<String> readList(String json) {
        return json == null ? List.of() : objectMapper.readerForListOf(String.class).readValue(json);
    }
}
