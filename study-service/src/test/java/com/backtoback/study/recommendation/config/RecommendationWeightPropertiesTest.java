package com.backtoback.study.recommendation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class RecommendationWeightPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("기본 가중치 40·30·10·20·10은 검증을 통과한다")
    void acceptsDefaultWeights() {
        var properties = new RecommendationWeightProperties(40, 30, 10, 20, 10);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("목표·레벨·언어·활동 시간대 가중치 합이 100이 아니면 검증에 실패한다")
    void rejectsTotalOtherThanMaxScore() {
        var properties = new RecommendationWeightProperties(40, 30, 10, 20, 20);

        Set<ConstraintViolation<RecommendationWeightProperties>> violations = validator.validate(properties);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("totalMaxScore");
    }

    @Test
    @DisplayName("레벨 차이 2 점수가 차이 0·1 점수보다 크면 검증에 실패한다")
    void rejectsLevelFarGreaterThanLevel() {
        var properties = new RecommendationWeightProperties(40, 30, 31, 20, 10);

        Set<ConstraintViolation<RecommendationWeightProperties>> violations = validator.validate(properties);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("levelFarWithinLevel");
    }

    @Test
    @DisplayName("음수 가중치는 검증에 실패한다")
    void rejectsNegativeWeight() {
        var properties = new RecommendationWeightProperties(50, 30, -1, 10, 10);

        Set<ConstraintViolation<RecommendationWeightProperties>> violations = validator.validate(properties);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("levelFar");
    }
}
