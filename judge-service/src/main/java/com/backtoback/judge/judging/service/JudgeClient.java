package com.backtoback.judge.judging.service;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.request.JudgeExecutionCreateRequest;

/** 실행 엔진에 코드를 접수하고 원시 실행 결과를 조회하는 계약을 정의한다. */
public interface JudgeClient {
    /**
     * 테스트 한 건의 실행을 생성하고 조회 token과 실제 사용한 실행 환경을 반환한다.
     * 자동 재시도하지 않는다. 통신 실패 시 생성 여부가 불명확하므로 호출자가 복구 정책을 적용한다.
     * 고정 실행 환경을 전달하면 ID·이름·버전을 검증해 재사용하고, 없으면 최신 환경으로 실행한다.
     *
     * @param request 언어·선택적 고정 실행 환경·실행 코드·입력·자원 제한
     * @return 결과 조회 token과 재채점을 위해 기록할 실행 환경
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 사용할 수 없는 언어를 요청한 경우
     *     또는 {@code SUBMISSION_RUNTIME_UNAVAILABLE}: 기존 실행 환경을 사용할 수 없거나 저장한 정보와 다른 경우
     *     또는 {@code JUDGE0_CONNECTION_FAILED}: 연결 실패 또는 통신 시간 초과가 발생한 경우
     *     또는 {@code JUDGE0_RESPONSE_FAILED}: HTTP 오류 또는 올바르지 않은 응답을 받은 경우
     *     또는 {@code JUDGE0_CONFIGURATION_INVALID}: 서버 주소가 설정되지 않은 경우
     */
    JudgeExecutionCreateResponse create(JudgeExecutionCreateRequest request);

    /**
     * 같은 token의 현재 결과를 한 번 조회한다. 조회 실패로 새 실행을 만들지 않는다.
     *
     * @param token 실행 생성 시 발급된 token
     * @return 실행 상태와 원시 결과; 최종 정답 판정과 다른 결과다
     * @throws JudgeException {@code JUDGE0_CONNECTION_FAILED}: 연결 실패 또는 통신 시간 초과가 발생한 경우
     *     또는 {@code JUDGE0_RESPONSE_FAILED}: HTTP 오류 또는 올바르지 않은 응답을 받은 경우
     *     또는 {@code JUDGE0_CONFIGURATION_INVALID}: 서버 주소가 설정되지 않은 경우
     */
    JudgeExecutionResultResponse find(String token);
}
