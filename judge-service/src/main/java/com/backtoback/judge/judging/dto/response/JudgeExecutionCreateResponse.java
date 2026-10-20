package com.backtoback.judge.judging.dto.response;

import com.backtoback.judge.judging.dto.JudgeRuntime;

/**
 * 생성한 실행의 조회 식별자와 실제 선택한 실행 환경을 반환한다.
 * 호출자는 재채점에 사용할 수 있도록 실행 환경을 작업 기록에 보관한다.
 *
 * @param token 결과 조회에 사용할 실행 식별자
 * @param runtime 해당 실행을 생성할 때 사용한 실행 환경
 */
public record JudgeExecutionCreateResponse(String token, JudgeRuntime runtime) {
}
