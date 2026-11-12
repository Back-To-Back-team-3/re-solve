package com.backtoback.study.hint.client;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * problem-service api-spec §2.6.6 미구현 기간 로컬 개발용 목 구현체.
 * 실 구현 완료 시 {@link ProblemHintContextRestClient}만 채우면 되며 이 클래스는 그대로 유지한다.
 */
@Component
@Profile("local")
public class ProblemHintContextMockClient implements ProblemHintContextClient {

    @Override
    public HintContextResponse getHintContext(Long problemId) {
        return new HintContextResponse(true, 1L, "[Mock] 최단 경로", "[Mock] 두 노드 사이의 최단 경로를 구하시오.", List.of());
    }
}
