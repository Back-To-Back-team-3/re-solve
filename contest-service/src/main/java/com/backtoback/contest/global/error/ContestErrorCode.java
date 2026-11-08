package com.backtoback.contest.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 내부 HTTP에서 사용하는 Contest 오류 코드. (에러 코드 표 > Exam · Contest 및 Member · Problem)
 */
@Getter
@RequiredArgsConstructor
public enum ContestErrorCode implements ErrorCode {
    CONTEXT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "내부 문맥에 접근할 권한이 없습니다."),
    CONTEXT_NOT_FOUND(HttpStatus.NOT_FOUND, "내부 문맥을 찾을 수 없습니다."),
    PROBLEM_REVISION_NOT_INCLUDED(HttpStatus.NOT_FOUND, "문맥에 고정된 문제 회차가 아닙니다."),
    EXAM_NOT_FOUND(HttpStatus.NOT_FOUND, "시험을 찾을 수 없습니다."),
    EXAM_SUBMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "시험 접수를 찾을 수 없습니다."),
    REJUDGE_NOT_ALLOWED(HttpStatus.CONFLICT, "재처리할 수 없는 제출입니다."),
    EXAM_STUDY_CLOSED(HttpStatus.CONFLICT, "종료된 스터디에서는 시험을 변경할 수 없습니다."),
    EXAM_PROBLEM_NOT_AVAILABLE(HttpStatus.CONFLICT, "선택할 수 없는 문제가 있습니다."),
    EXAM_PROBLEM_NOT_INCLUDED(HttpStatus.NOT_FOUND, "시험에 포함되지 않은 문제입니다."),
    PROBLEM_NOT_FOUND(HttpStatus.NOT_FOUND, "문제나 이미지를 찾을 수 없습니다."),
    CODE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "코드 크기가 제한을 초과했습니다."),
    DRAFT_SEQ_CONFLICT(HttpStatus.CONFLICT, "Draft 순번이 최신 상태와 일치하지 않습니다."),
    RESULT_CURSOR_EXPIRED(HttpStatus.CONFLICT, "결과가 변경되어 커서가 만료되었습니다."),
    EXAM_RESULT_NOT_AVAILABLE(HttpStatus.CONFLICT, "시험 결과를 아직 조회할 수 없습니다."),
    SUBMISSION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "제출 요청이 너무 많습니다."),
    UNSUPPORTED_LANGUAGE(HttpStatus.BAD_REQUEST, "지원하지 않는 언어입니다."),
    RUN_IN_PROGRESS(HttpStatus.TOO_MANY_REQUESTS, "코드 실행이 진행 중입니다."),
    RUN_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "코드 실행 시간이 초과되었습니다."),
    EXAM_MODIFICATION_CLOSED(HttpStatus.CONFLICT, "시험을 수정할 수 있는 시간이 지났습니다."),
    EXAM_REVISION_CONFLICT(HttpStatus.CONFLICT, "시험 버전이 일치하지 않습니다."),
    CONTEST_DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "외부 서비스를 사용할 수 없습니다."),
    MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 회원입니다."),
    EXAM_NOT_REGISTERED(HttpStatus.FORBIDDEN, "시험에 등록하지 않았습니다."),
    EXAM_NOT_STARTED(HttpStatus.FORBIDDEN, "시험이 아직 시작되지 않았습니다."),
    EXAM_ALREADY_FINISHED(HttpStatus.CONFLICT, "이미 종료한 시험입니다."),
    EXAM_CANCELED(HttpStatus.CONFLICT, "취소된 시험입니다."),
    EXAM_PARTICIPATION_CANCELLATION_CLOSED(HttpStatus.CONFLICT, "참가를 취소할 수 없습니다."),
    EXAM_NOT_ELIGIBLE(HttpStatus.FORBIDDEN, "시험에 참가할 자격이 없습니다."),
    // 기존 활성 참가 재요청은 허용하고, 이벤트 수신자 상한에 도달한 시험의 신규 등록만 거절한다.
    EXAM_PARTICIPANT_LIMIT_REACHED(HttpStatus.CONFLICT, "시험 참가 인원이 한도에 도달했습니다."),
    EXAM_CLOSED(HttpStatus.CONFLICT, "시험의 요청 시간이 종료되었습니다."),
    CONTEST_CLOSED(HttpStatus.CONFLICT, "대회의 요청 시간이 종료되었습니다."),
    WITHDRAWAL_BLOCKED_ACTIVE_EXAM(HttpStatus.CONFLICT, "실제 입장한 미확정 시험·대회가 있습니다."),
    PROBLEM_IN_ACTIVE_EXAM(HttpStatus.CONFLICT, "예정·진행 중인 시험·대회에서 사용하는 문제입니다."),
    CONTEST_OPERATION_BLOCKED(HttpStatus.CONFLICT, "다른 자원 작업이 진행 중입니다."),
    OPERATION_NOT_CURRENT(HttpStatus.CONFLICT, "현재 자원 작업과 일치하지 않습니다."),
    SOURCE_VERSION_CONFLICT(HttpStatus.CONFLICT, "원천 상태 버전이 일치하지 않습니다."),
    OPERATION_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 반대 결과로 종결된 작업입니다."),
    SUBMISSION_NOT_SHAREABLE(HttpStatus.CONFLICT, "공유 가능한 확정 AC 제출이 아닙니다."),
    RESULT_NOT_FINALIZED(HttpStatus.CONFLICT, "결과가 확정되지 않았습니다."),
    RESULT_REVISION_NOT_FOUND(HttpStatus.NOT_FOUND, "확정 결과 버전을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
