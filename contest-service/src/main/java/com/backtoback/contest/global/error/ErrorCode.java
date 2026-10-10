package com.backtoback.contest.global.error;

import lombok.Getter;

/**
 * 이번 시험 API 범위의 오류 코드와 HTTP 상태를 연결한다.
 * 예외 메시지는 안내용이며 클라이언트는 code로 분기한다.
 * <p>기준 문서: 에러 코드 표 / Common · Auth, Exam · Contest: 오류 코드와 HTTP 상태.
 * API 명세서 v1.1 / §4.1.1 시험 생성: CONTEST_DEPENDENCY_UNAVAILABLE은 API 절의 코드를 따른다.
 */
@Getter
public enum ErrorCode {
    // HTTP 요청 형식·필드·교차 조건 검증 실패
    COMMON_INVALID_REQUEST(400, "입력값을 확인해 주세요."),

    // 예상하지 못한 내부 오류
    COMMON_INTERNAL_SERVER_ERROR(500, "서버 내부 오류가 발생했습니다."),

    // Gateway 사용자 식별 정보 누락·오류
    AUTH_TOKEN_INVALID(401, "인증 정보가 없거나 유효하지 않습니다."),

    // 시험 생성·조회 권한 없음
    AUTH_ACCESS_DENIED(403, "접근 권한이 없습니다."),

    // 시험 식별자에 대응하는 문맥 없음
    EXAM_NOT_FOUND(404, "시험을 찾을 수 없습니다."),

    // 등록 시 승인 구성원 복제본 없음
    EXAM_NOT_ELIGIBLE(403, "시험 참가 자격이 없습니다."),

    // 활성 등록 없이 입장·문제 조회
    EXAM_NOT_REGISTERED(403, "시험 참가 등록이 필요합니다."),

    // 시작 전 입장·문제 조회
    EXAM_NOT_STARTED(403, "아직 시험이 시작되지 않았습니다."),

    // 전체 또는 개인 마감 이후 변경
    EXAM_CLOSED(409, "시험이 종료되었습니다."),

    // 취소 문맥에 신규 요청
    EXAM_CANCELED(409, "취소된 시험입니다."),

    // 개인 종료 후 새 입장
    EXAM_ALREADY_FINISHED(409, "이미 시험을 종료했습니다."),

    // 스터디 종료 또는 종료 가드
    EXAM_STUDY_CLOSED(409, "종료되었거나 종료 준비 중인 스터디입니다."),

    // 현재 공개 회차를 고정할 수 없음
    EXAM_PROBLEM_NOT_AVAILABLE(409, "시험에 사용할 수 없는 문제입니다."),

    // MEMBER·STUDY·PROBLEM 가드 충돌
    CONTEST_OPERATION_BLOCKED(409, "자원 작업으로 시험 요청이 차단되었습니다."),

    // API §4.1.1에 정의된 생성 의존성 실패
    CONTEST_DEPENDENCY_UNAVAILABLE(503, "시험 생성에 필요한 서비스를 조회할 수 없습니다."),

    // 고정 회차 본문 등 내부 조회 실패
    COMMON_DEPENDENCY_UNAVAILABLE(503, "필요한 서비스를 조회할 수 없습니다.");

    // 공통 오류 응답을 응답할 HTTP 상태 값.
    private final int status;

    // 내부 예외 내용이나 인증 비밀값을 포함하지 않는 사용자 안내.
    private final String message;

    /**
     * 명세의 오류 코드별 응답 상태와 안내 문구를 보관한다.
     *
     * @param status HTTP 상태 코드
     * @param message 사용자 안내 문구
     */
    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }
}
