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
