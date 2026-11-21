package com.backtoback.study.recommendation.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.backtoback.study.recommendation.domain.SkillLevelRank;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * 추천 점수 계산에 쓰는 회원 학습 프로필 값.
 *
 * @param goal 학습 목표, 미입력이면 {@code null}
 * @param levelRank 진입한 태그 레벨 중 최고 순위(1~3), 유효한 레벨이 없으면 {@code null}
 * @param languages 정규화한 선호 언어
 * @param timeSlots 활동 시간대
 */
public record RecommendationProfile(String goal, Integer levelRank, Set<String> languages, Set<String> timeSlots) {

    private static final Logger log = LoggerFactory.getLogger(RecommendationProfile.class);

    /**
     * 복제본 JSON 컬럼을 읽는다. 복제본은 값을 검사하지 않고 저장하므로(이벤트 계약서 M4), 읽을 수 없는 컬럼은
     * 예외 대신 미입력으로 보고 해당 항목만 점수에서 빠지게 한다.
     */
    public static RecommendationProfile from(MemberLearningProfileReplica replica, ObjectMapper objectMapper) {
        Long memberId = replica.getMemberId();
        JavaType tagLevelsType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class);
        JavaType valuesType = objectMapper.getTypeFactory().constructCollectionType(List.class, String.class);
        Map<String, String> tagLevels
            = read(objectMapper, replica.getTagLevelsJson(), tagLevelsType, Map.of(), memberId);
        List<String> languages
            = read(objectMapper, replica.getPreferredLanguagesJson(), valuesType, List.of(), memberId);
        List<String> timeSlots = read(objectMapper, replica.getActiveTimeSlotsJson(), valuesType, List.of(), memberId);

        // 스터디는 태그와 무관한 단일 수준이므로 회원은 진입한 태그 중 최고 레벨로 비교한다(정책 및 상태 8.2 스터디 추천).
        OptionalInt maxLevel
            = tagLevels
                .values()
                .stream()
                .map(SkillLevelRank::of)
                .filter(OptionalInt::isPresent)
                .mapToInt(OptionalInt::getAsInt)
                .max();

        return new RecommendationProfile(
            replica.getGoal(),
            maxLevel.isPresent() ? maxLevel.getAsInt() : null,
            languages
                .stream()
                .map(RecommendationProfile::normalizeLanguage)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet()),
            timeSlots.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet())
        );
    }

    /**
     * 스터디 선호 언어는 값 목록 검증이 없어 표기가 섞일 수 있으므로 앞뒤 공백과 대소문자를 무시하고 비교한다.
     *
     * @return 정규화한 언어, 비어 있으면 {@code null}
     */
    public static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        return language.strip().toLowerCase(Locale.ROOT);
    }

    private static <T> T read(ObjectMapper objectMapper, String json, JavaType type, T empty, Long memberId) {
        if (json == null) {
            return empty;
        }
        try {
            T value = objectMapper.readValue(json, type);
            return value == null ? empty : value;
        } catch (JacksonException e) {
            // 원문에 회원 입력값이 있을 수 있으므로 예외 종류만 기록한다.
            log.warn("학습 프로필 복제본 JSON 읽기 실패, 미입력으로 처리 memberId={} cause={}", memberId, e.getClass().getSimpleName());
            return empty;
        }
    }
}
