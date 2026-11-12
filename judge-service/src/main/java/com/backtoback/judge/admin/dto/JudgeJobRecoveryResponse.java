package com.backtoback.judge.admin.dto;

/** 최초 복구 요청의 응답. 이후 작업이 실행돼도 같은 키에는 이 상태를 반환한다. */
public record JudgeJobRecoveryResponse(String judgeJobId, String status) {
    /**
     * 복구 결과의 작업 ID를 문자열 응답으로 변환한다.
     *
     * @param jobId 복구한 작업 ID
     * @param status 최초 성공 응답 상태
     * @return 복구 접수 응답
     */
    public static JudgeJobRecoveryResponse from(long jobId, String status) {
        return new JudgeJobRecoveryResponse(Long.toString(jobId), status);
    }
}
