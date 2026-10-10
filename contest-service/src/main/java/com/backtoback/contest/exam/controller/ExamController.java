package com.backtoback.contest.exam.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.request.CreateExamRequest;
import com.backtoback.contest.exam.dto.response.ExamResponses;
import com.backtoback.contest.exam.service.ExamService;
import com.backtoback.contest.global.error.ErrorCode;
import com.backtoback.contest.global.error.ExamApiException;
import com.backtoback.contest.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * 시험의 여섯 외부 HTTP 계약을 서비스 인터페이스에 연결한다.
 * 요청자는 바디가 아닌 Gateway의 X-User-Id에서 받는다. 여기서는 헤더 형식만 검사하며 JWT 검증은 하지 않는다.
 * 현재는 개발용 계약 확인 단계이므로 dev와 exam-stub을 함께 켰을 때만 경로를 등록한다.
 * 운영 서비스 구현 후 프로필 제한을 제거하는 일은 실제 기능 구현 범위에 포함한다.
 * <p>기준 문서: API 명세서 v1.1 / §0.1 기본 규칙, §0.2 식별자(ID) 규칙,
 * §4.1.1~§4.1.3, §4.2.1~§4.2.3: 경로·응답 상태·Gateway 요청자·입력 계약.
 */
@RestController
@RequestMapping("/api/v1")
@Profile("dev & exam-stub & !prod & !production")
@RequiredArgsConstructor
public class ExamController {
    private final ExamService examService;

    /**
     * 생성 입력을 검증하고 서비스 결과를 201로 반환한다.
     *
     * @param studyId 대상 스터디 문자열 ID
     * @param memberId Gateway 사용자 ID
     * @param request 검증할 시험 설정
     * @return 생성 성공 봉투
     * @throws ExamApiException 식별자가 잘못됐거나 서비스가 명세 오류를 반환한 경우
     */
    @PostMapping("/studies/{studyId}/exams")
    @Operation(
        summary = "시험 생성",
        description = "개발 stub 계약 확인용. 실제 저장·권한 판정은 후속 구현."
    )
    public ResponseEntity<ApiResponse<ExamResponses.Created>> create(
        @PathVariable String studyId,
        @RequestHeader("X-User-Id") String memberId,
        @Valid @RequestBody CreateExamRequest request
    ) {
        ExamResponses.Created response
            = examService.create(identifier(studyId, false), identifier(memberId, true), request);
        return ResponseEntity.status(201).body(ApiResponse.success("시험이 생성되었습니다.", response));
    }

    /**
     * 목록 입력 기본값과 허용 범위를 확인한다. 문제 ID·본문은 목록 DTO에 포함되지 않는다.
     *
     * @param studyId 대상 스터디 ID
     * @param memberId Gateway 사용자 ID
     * @param status 선택 상태 필터
     * @param page 0부터의 페이지
     * @param size 기본 20, 최대 100
     * @param sort startsAt·createdAt과 asc·desc 조합
     * @return 공통 페이지 형식의 시험 목록
     * @throws ExamApiException 식별자 또는 서비스 판정 오류
     */
    @GetMapping("/studies/{studyId}/exams")
    @Operation(summary = "스터디 시험 목록")
    public ApiResponse<ExamResponses.Page> list(
        @PathVariable String studyId,
        @RequestHeader("X-User-Id") String memberId,
        @RequestParam(required = false) ExamStatus status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "startsAt,desc")
        @Pattern(regexp = "(?:startsAt|createdAt),(?:asc|desc)") String sort
    ) {
        return ApiResponse
            .success(
                null,
                examService.list(identifier(studyId, false), identifier(memberId, true), status, page, size, sort)
            );
    }

    /**
     * 입장 상태를 바꾸지 않는 상세 조회를 연결한다.
     *
     * @param examId 시험 ID
     * @param memberId Gateway 사용자 ID
     * @return 설정과 본인 참가 정보를 담은 상세
     * @throws ExamApiException 식별자 또는 서비스 판정 오류
     */
    @GetMapping("/exams/{examId}")
    @Operation(summary = "시험 상세")
    public ApiResponse<ExamResponses.Detail> getDetail(
        @PathVariable String examId,
        @RequestHeader("X-User-Id") String memberId
    ) {
        return ApiResponse.success(null, examService.getDetail(identifier(examId, false), identifier(memberId, true)));
    }

    /**
     * 신규 참가와 기존 활성 참가의 HTTP 상태를 구분한다. 바디에서 요청자 ID를 받지 않는다.
     *
     * @param examId 시험 ID
     * @param memberId Gateway 사용자 ID
     * @return 신규 등록은 201, 기존 참가 반환은 200
     * @throws ExamApiException 식별자 또는 서비스 판정 오류
     */
    @PostMapping("/exams/{examId}/participants")
    @Operation(summary = "시험 참가 등록")
    public ResponseEntity<ApiResponse<ExamResponses.Registered>> register(
        @PathVariable String examId,
        @RequestHeader("X-User-Id") String memberId
    ) {
        ExamService.Registration result = examService.register(identifier(examId, false), identifier(memberId, true));
        return ResponseEntity
            .status(result.created() ? 201 : 200)
            .body(ApiResponse.success("참가 등록이 완료되었습니다.", result.data()));
    }

    /**
     * 활성 등록 본인의 입장 서비스 계약을 연결한다.
     *
     * @param examId 시험 ID
     * @param memberId Gateway 사용자 ID
     * @return 최초 입장·개인 마감 정보를 가진 응답
     * @throws ExamApiException 식별자 또는 서비스 판정 오류
     */
    @PostMapping("/exams/{examId}/start")
    @Operation(summary = "시험 입장")
    public ApiResponse<ExamResponses.Started> start(
        @PathVariable String examId,
        @RequestHeader("X-User-Id") String memberId
    ) {
        return ApiResponse.success(null, examService.start(identifier(examId, false), identifier(memberId, true)));
    }

    /**
     * 입장한 본인의 고정 문제 회차 조회를 연결한다. Controller에서 외부 문제 서비스를 직접 호출하지 않는다.
     *
     * @param examId 시험 ID
     * @param memberId Gateway 사용자 ID
     * @return 공개 본문·함수 명세·예제
     * @throws ExamApiException 식별자 또는 서비스 판정 오류
     */
    @GetMapping("/exams/{examId}/problems")
    @Operation(summary = "시험 문제 목록·본문")
    public ApiResponse<ExamResponses.Problems> getProblems(
        @PathVariable String examId,
        @RequestHeader("X-User-Id") String memberId
    ) {
        return ApiResponse
            .success(null, examService.getProblems(identifier(examId, false), identifier(memberId, true)));
    }

    /**
     * 양의 BIGINT 문자열을 확인한다. 인증 식별자 실패는 401, 경로 식별자 실패는 400으로 구분한다.
     *
     * @param value 확인할 식별자
     * @param authentication Gateway 사용자 헤더인지 여부
     * @return 변환하지 않은 식별자 문자열
     * @throws ExamApiException 숫자 형식·범위가 유효하지 않은 경우
     */
    private String identifier(String value, boolean authentication) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ExamApiException(
                authentication ? ErrorCode.AUTH_TOKEN_INVALID : ErrorCode.COMMON_INVALID_REQUEST
            );
        }
    }
}
