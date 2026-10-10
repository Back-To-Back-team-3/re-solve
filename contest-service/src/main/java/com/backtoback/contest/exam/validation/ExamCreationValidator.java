package com.backtoback.contest.exam.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import com.backtoback.contest.exam.dto.request.CreateExamRequest;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 종료·시작 간격과 문제 목록의 중복·연속 순서·총점을 검사한다.
 * null·형식 오류는 필드 제약에 맡겨 중복 오류나 파싱 예외를 만들지 않는다.
 * 미래 시작 여부는 startsAt의 Future 제약이 처리하며 현재 시각을 이중 조회하지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §4.1.1 시험 생성: 1~180분·배점 합계·문제 순서.
 */
public class ExamCreationValidator implements ConstraintValidator<ValidExamCreation, CreateExamRequest> {
    private static final BigDecimal MAX_SCORE = new BigDecimal("9999.99");

    /**
     * 형식이 맞는 값에만 교차 조건을 적용하고 관련 필드에 오류를 추가한다.
     *
     * @param request 생성 요청, null은 다른 제약에 맡김
     * @param context 필드 오류를 추가할 검증 문맥
     * @return 모든 검사한 교차 조건을 만족하면 true
     */
    @Override
    public boolean isValid(CreateExamRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = validateDuration(request, context);
        return validateProblems(request, context) && valid;
    }

    /**
     * 시차가 다른 입력도 실제 순간의 차이로 1~180분인지 확인한다.
     *
     * @param request 시작·종료가 있는 요청
     * @param context 오류 기록 문맥
     * @return 두 시각이 없거나 유효한 기간이면 true
     */
    private boolean validateDuration(CreateExamRequest request, ConstraintValidatorContext context) {
        if (request.startsAt() == null || request.endsAt() == null) {
            return true;
        }
        Duration duration = Duration.between(request.startsAt(), request.endsAt());
        if (duration.compareTo(Duration.ofMinutes(1)) < 0 || duration.compareTo(Duration.ofMinutes(180)) > 0) {
            reject(context, "endsAt", "시험 시간은 시작 이후 1~180분이어야 합니다.");
            return false;
        }
        return true;
    }

    /**
     * 중복 없는 문제·1부터 N까지의 순서와 9999.99 이하의 양수 총점을 확인한다.
     * 필드 형식이 틀린 점수는 건너뛰고 해당 필드 제약에 오류 처리를 맡긴다.
     *
     * @param request 문제 목록이 있는 요청
     * @param context 오류 기록 문맥
     * @return 검사한 목록 조건을 만족하면 true
     */
    private boolean validateProblems(CreateExamRequest request, ConstraintValidatorContext context) {
        if (request.problems() == null || request.problems().isEmpty()) {
            return true;
        }
        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean valid = true;
        for (CreateExamRequest.Problem problem : request.problems()) {
            if (problem == null) {
                continue;
            }
            if (problem.problemId() != null && !ids.add(problem.problemId())) {
                reject(context, "problems", "같은 문제를 중복 지정할 수 없습니다.");
                valid = false;
            }
            if (problem.problemId() != null && problem.problemId().matches("[1-9][0-9]{0,18}")
                && new BigInteger(problem.problemId()).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                reject(context, "problems", "문제 ID는 양의 BIGINT 범위여야 합니다.");
                valid = false;
            }
            Integer order = problem.displayOrder();
            if (order != null && (order < 1 || order > request.problems().size() || !orders.add(order))) {
                reject(context, "problems", "표시 순서는 1부터 문제 수까지 중복 없이 연속이어야 합니다.");
                valid = false;
            }
            if (problem.score() != null && problem.score().matches("(?:0|[1-9][0-9]{0,3})\\.[0-9]{2}")) {
                BigDecimal score = new BigDecimal(problem.score());
                if (score.signum() <= 0) {
                    reject(context, "problems", "문제 배점은 0.01 이상이어야 합니다.");
                    valid = false;
                }
                total = total.add(score);
            }
        }
        if (total.compareTo(MAX_SCORE) > 0) {
            reject(context, "problems", "배점 합계는 9999.99 이하여야 합니다.");
            valid = false;
        }
        return valid;
    }

    /**
     * 입력값을 노출하지 않고 검증 조건과 관련 필드만 오류에 넣는다.
     *
     * @param context 오류 기록 대상
     * @param field 관련 최상위 요청 필드
     * @param message 실패 조건 안내
     */
    private void reject(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
    }
}
