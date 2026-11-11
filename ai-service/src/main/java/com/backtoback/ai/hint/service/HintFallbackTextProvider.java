package com.backtoback.ai.hint.service;

import java.util.Map;

import org.springframework.stereotype.Component;

/** LLM 타임아웃·실패 시 반환할 단계별 정적 폴백 텍스트. */
@Component
public class HintFallbackTextProvider {

    private static final Map<Integer, String> FALLBACK_TEXTS
        = Map
            .of(
                1,
                "이 문제의 접근 방향을 생각해 보세요. " + "문제에서 요구하는 조건을 꼼꼼히 읽고, 어떤 자료구조나 알고리즘이 적합할지 고민해 보세요. "
                    + "힌트 생성 중 오류가 발생하여 일반 안내로 대체되었습니다.",
                2,
                "핵심 아이디어를 찾아보세요. " + "비슷한 유형의 문제를 풀었던 경험을 떠올리고, 반복되는 패턴이나 최적 부분 구조가 있는지 확인해 보세요. "
                    + "힌트 생성 중 오류가 발생하여 일반 안내로 대체되었습니다.",
                3,
                "풀이 절차를 단계별로 세워 보세요. " + "입력 처리 → 핵심 로직 → 결과 출력 순서로 구조를 잡고, 각 단계에서 주의할 엣지 케이스를 점검해 보세요. "
                    + "힌트 생성 중 오류가 발생하여 일반 안내로 대체되었습니다."
            );

    public String get(int level) {
        return FALLBACK_TEXTS.getOrDefault(level, FALLBACK_TEXTS.get(1));
    }
}
