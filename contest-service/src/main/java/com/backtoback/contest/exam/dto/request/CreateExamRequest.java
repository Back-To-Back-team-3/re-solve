package com.backtoback.contest.exam.dto.request;

import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.contest.exam.validation.ValidExamCreation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 시험 생성의 HTTP 입력을 받는다. 문자열 ID·점수와 시차를 포함한 시각을 그대로 유지한다.
 * 개별 필드를 먼저 검증하고 문제 중복·순서·합계·시험 시간은 교차 검증으로 확인한다.
 * 문제 회차 선택과 DB 저장·생성 권한 판정은 후속 서비스 책임이다.
 * <p>기준 문서: API 명세서 v1.1 / §4.1.1 시험 생성: 요청 필드와 입력 제한.
 *
 * @param title 1~200자 제목
 * @param mode FIXED만 허용
 * @param startsAt 현재 이후의 시작 시각
 * @param endsAt 시작보다 늦은 종료 시각
 * @param problems 중복 없는 1~20개 문제 배점과 연속 순서
 */
@ValidExamCreation
public record CreateExamRequest(
    @NotBlank @Size(max = 200) String title,
    @NotNull @Pattern(regexp = "FIXED") String mode,
    @NotNull @Future OffsetDateTime startsAt,
    @NotNull OffsetDateTime endsAt,
    @NotNull
    @Size(
        min = 1,
        max = 20
    ) List<@NotNull @Valid Problem> problems
) {
    /**
     * 고정할 문제와 배점·순서를 지정한다. 회차 ID는 외부 요약 조회에서 결정하므로 입력받지 않는다.
     *
     * @param problemId 양의 BIGINT 식별자 문자열
     * @param score 소수점 둘째 자리까지 명시한 0.01~9999.99 점수
     * @param displayOrder 1부터 문제 수까지의 표시 순서
     */
    public record Problem(
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String problemId,
        @NotNull @Pattern(regexp = "(?:0|[1-9][0-9]{0,3})\\.[0-9]{2}") String score,
        @NotNull @jakarta.validation.constraints.Min(1) Integer displayOrder
    ) {
    }
}
