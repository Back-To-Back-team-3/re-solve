package com.backtoback.study.hint.client;

/** problem-service 힌트 문맥 조회 클라이언트 (api-spec §2.6.6). */
public interface ProblemHintContextClient {

    HintContextResponse getHintContext(Long problemId);
}
