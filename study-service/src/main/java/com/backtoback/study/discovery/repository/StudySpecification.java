package com.backtoback.study.discovery.repository;

import org.springframework.data.jpa.domain.Specification;

import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyStatus;

/**
 * 스터디 목록 조회(API 명세서 5.1.1)의 선택 필터를 동적으로 조합한다.
 * 각 메서드는 조건이 없으면 {@code null}을 반환한다. 이 버전의 Spring Data JPA는 {@code Specification.and(null)}을
 * 허용하지 않으므로, 호출하는 쪽({@code StudyQueryService})에서 {@code null}을 걸러낸 뒤 조합해야 한다.
 */
public final class StudySpecification {

    private StudySpecification() {}

    public static Specification<Study> statusEquals(StudyStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Study> keywordContains(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String pattern = "%" + keyword + "%";
        return (root, query, cb) -> cb
            .or(cb.like(root.get("name"), pattern), cb.like(root.get("description"), pattern));
    }

    public static Specification<Study> goalEquals(String goal) {
        return equalsOrNull("goal", goal);
    }

    public static Specification<Study> skillLevelEquals(String skillLevel) {
        return equalsOrNull("skillLevel", skillLevel);
    }

    public static Specification<Study> preferredLanguageEquals(String preferredLanguage) {
        return equalsOrNull("preferredLanguage", preferredLanguage);
    }

    public static Specification<Study> activeTimeSlotEquals(String activeTimeSlot) {
        return equalsOrNull("activeTimeSlot", activeTimeSlot);
    }

    private static Specification<Study> equalsOrNull(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get(field), value);
    }
}
