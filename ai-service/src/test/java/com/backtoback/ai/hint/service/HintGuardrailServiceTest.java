package com.backtoback.ai.hint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import com.backtoback.ai.hint.dto.HintRequestedPayload;

@ExtendWith(MockitoExtension.class)
class HintGuardrailServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ChatClient chatClient;
    @Mock
    HintPromptTemplate hintPromptTemplate;

    HintGuardrailService sut;

    @BeforeEach
    void setUp() {
        sut = new HintGuardrailService(Optional.of(chatClient), hintPromptTemplate);
    }

    @Test
    @DisplayName("코드 미포함 응답 → 감지 없이 pass(원문) 반환")
    void check_noCode_returnsPassWithOriginalText() {
        HintRequestedPayload event = makeEvent();
        String cleanText = "이 문제는 그리디 접근 방식으로 풀 수 있습니다.";

        GuardrailResult result = sut.check(cleanText, event);

        assertThat(result.passed()).isTrue();
        assertThat(result.text()).isEqualTo(cleanText);
        then(chatClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("코드 포함 응답 → 재생성 성공(코드 없음) → pass(재생성문) 반환")
    void check_codeDetected_regenerationSucceeds_returnsPassWithRegeneratedText() {
        HintRequestedPayload event = makeEvent();
        String codeText = "for(int i=0; i<n; i++) 반복합니다.";
        String cleanRegenerated = "반복 횟수를 세어 최적값을 찾는 방식으로 접근하세요.";

        given(hintPromptTemplate.systemPrompt()).willReturn("system");
        given(hintPromptTemplate.userPrompt(anyInt(), anyString(), anyString(), any())).willReturn("user");
        given(chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn(cleanRegenerated);

        GuardrailResult result = sut.check(codeText, event);

        assertThat(result.passed()).isTrue();
        assertThat(result.text()).isEqualTo(cleanRegenerated);
    }

    @Test
    @DisplayName("코드 포함 → 재생성 결과에도 코드 포함 → fallback() 반환")
    void check_codeDetected_regenerationAlsoContainsCode_returnsFallback() {
        HintRequestedPayload event = makeEvent();
        String codeText = "if (x > 0) 처리합니다.";
        String regenWithCode = "while(true) { break; } 형태로 처리합니다.";

        given(hintPromptTemplate.systemPrompt()).willReturn("system");
        given(hintPromptTemplate.userPrompt(anyInt(), anyString(), anyString(), any())).willReturn("user");
        given(chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn(regenWithCode);

        GuardrailResult result = sut.check(codeText, event);

        assertThat(result.passed()).isFalse();
        assertThat(result.text()).isNull();
    }

    @Test
    @DisplayName("코드 포함 + ChatClient 없음 → 재생성 불가, fallback() 반환")
    void check_codeDetected_noChatClient_returnsFallback() {
        HintGuardrailService sutNoChatClient = new HintGuardrailService(Optional.empty(), hintPromptTemplate);
        HintRequestedPayload event = makeEvent();
        String codeText = "public static void main() {}";

        GuardrailResult result = sutNoChatClient.check(codeText, event);

        assertThat(result.passed()).isFalse();
    }

    @Test
    @DisplayName("코드 포함 → 재생성 호출이 10초 초과 → fallback() 반환")
    void check_codeDetected_regenerationTimesOut_returnsFallback() {
        HintRequestedPayload event = makeEvent();
        String codeText = "return null;";

        given(hintPromptTemplate.systemPrompt()).willReturn("system");
        given(hintPromptTemplate.userPrompt(anyInt(), anyString(), anyString(), any())).willReturn("user");
        given(chatClient.prompt().system(anyString()).user(anyString()).call().content()).willAnswer(inv -> {
            try {
                Thread.sleep(11_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "지연 응답";
        });

        GuardrailResult result = sut.check(codeText, event);

        assertThat(result.passed()).isFalse();
    }

    private HintRequestedPayload makeEvent() {
        return new HintRequestedPayload(
            "evt-001",
            "HintRequested",
            "1",
            "2026-11-13T00:00:00Z",
            "req-001",
            "corr-001",
            "req-001",
            "mem-001",
            "prob-001",
            "rev-001",
            1,
            "문제 제목",
            "문제 내용",
            List.of("dp", "greedy")
        );
    }
}
