package com.backtoback.ai.hint.service;

import java.util.List;

import org.springframework.stereotype.Component;

/** 단계별 LLM 힌트 프롬프트 생성기. 코드를 포함하지 않는 개념 중심 힌트를 유도한다. */
@Component
public class HintPromptTemplate {

    private static final String SYSTEM_PROMPT = """
        당신은 알고리즘 학습을 돕는 멘토입니다.
        주어진 문제에 대한 힌트를 제공할 때 다음 규칙을 반드시 지키세요:
        - 정답 코드나 구체적인 구현 코드를 절대 포함하지 마세요.
        - 접근 방법, 아이디어, 절차를 자연어로만 설명하세요.
        - 한국어로 답변하세요.
        - 답변은 200자 이내로 간결하게 작성하세요.
        """;

    private static final String LEVEL_1_TEMPLATE = """
        문제: %s
        알고리즘 태그: %s

        위 문제를 풀기 위한 접근 방향 힌트를 제공해 주세요.
        어떤 유형의 문제인지, 어떤 관점으로 접근하면 좋을지 알려주세요.
        구체적인 풀이 방법이나 코드는 포함하지 마세요.
        """;

    private static final String LEVEL_2_TEMPLATE = """
        문제: %s
        알고리즘 태그: %s
        문제 내용: %s

        위 문제를 풀기 위한 핵심 아이디어 힌트를 제공해 주세요.
        어떤 알고리즘이나 자료구조를 활용하면 효율적으로 풀 수 있는지 알려주세요.
        구체적인 구현 방법이나 코드는 포함하지 마세요.
        """;

    private static final String LEVEL_3_TEMPLATE = """
        문제: %s
        알고리즘 태그: %s
        문제 내용: %s

        위 문제를 풀기 위한 풀이 절차 힌트를 제공해 주세요.
        단계별로 무엇을 처리해야 하는지 순서를 알려주세요.
        코드 없이 절차와 주의할 점만 설명해 주세요.
        """;

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String userPrompt(int level, String title, String body, List<String> tags) {
        String tagText = (tags == null || tags.isEmpty()) ? "없음" : String.join(", ", tags);
        return switch (level) {
            case 1 -> LEVEL_1_TEMPLATE.formatted(title, tagText);
            case 2 -> LEVEL_2_TEMPLATE.formatted(title, tagText, body);
            case 3 -> LEVEL_3_TEMPLATE.formatted(title, tagText, body);
            default -> LEVEL_1_TEMPLATE.formatted(title, tagText);
        };
    }
}
