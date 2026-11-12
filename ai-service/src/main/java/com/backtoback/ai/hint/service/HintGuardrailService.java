package com.backtoback.ai.hint.service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.backtoback.ai.hint.dto.HintRequestedPayload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** LLM 응답의 코드 포함 여부를 감지하고, 감지 시 1회 재생성 후 폴백 여부를 반환한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HintGuardrailService {

    private static final Pattern CODE_PATTERN
        = Pattern
            .compile(
                "\\b(if|for|while|switch|elif|def|class|public|private|static|struct|"
                    + "int|long|void|boolean|return|null|auto|nullptr|" + "import|print|printf|cout|cin|System)\\b"
                    + "|[{}`;]|#include|//|->|\\+=|-=|\\*="
            );

    private static final String REGEN_SUFFIX = "\n\n이전 응답에 코드가 감지되었습니다. 반드시 코드 없이 처음부터 개념 설명만 작성하세요.";

    private final Optional<ChatClient> chatClient;
    private final HintPromptTemplate hintPromptTemplate;

    public GuardrailResult check(String hintText, HintRequestedPayload event) {
        if (hintText == null || hintText.isBlank()) {
            log.warn("LLM 응답 비어 있음, 폴백 전이 hintRequestId={}", event.hintRequestId());
            return GuardrailResult.fallback();
        }
        if (!containsCode(hintText)) {
            return GuardrailResult.pass(hintText);
        }

        log.warn("가드레일 코드 감지, 재생성 시도 hintRequestId={} level={}", event.hintRequestId(), event.level());

        if (chatClient.isEmpty()) {
            log.warn("ChatClient 없음, 재생성 불가 hintRequestId={}", event.hintRequestId());
            return GuardrailResult.fallback();
        }

        String regenerated;
        try {
            regenerated = callRegenerationWithTimeout(event);
        } catch (Exception e) {
            log.warn("재생성 실패 또는 타임아웃 hintRequestId={} error={}", event.hintRequestId(), e.getMessage());
            return GuardrailResult.fallback();
        }

        if (regenerated == null || regenerated.isBlank()) {
            log.warn("재생성 응답 비어 있음, 폴백 전이 hintRequestId={}", event.hintRequestId());
            return GuardrailResult.fallback();
        }
        if (!containsCode(regenerated)) {
            log.info("재생성 성공, 가드레일 통과 hintRequestId={}", event.hintRequestId());
            return GuardrailResult.pass(regenerated);
        }

        log.warn("재생성 결과에도 코드 감지, 폴백 전이 hintRequestId={}", event.hintRequestId());
        return GuardrailResult.fallback();
    }

    private boolean containsCode(String text) {
        return CODE_PATTERN.matcher(text).find();
    }

    private String callRegenerationWithTimeout(HintRequestedPayload event) {
        String userPrompt
            = hintPromptTemplate
                .userPrompt(event.level(), event.problemTitle(), event.problemBody(), event.problemTags())
                + REGEN_SUFFIX;

        CompletableFuture<String> future
            = CompletableFuture
                .supplyAsync(
                    () -> chatClient
                        .get()
                        .prompt()
                        .system(hintPromptTemplate.systemPrompt())
                        .user(userPrompt)
                        .call()
                        .content()
                );
        try {
            return future.orTimeout(10, TimeUnit.SECONDS).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("재생성 인터럽트", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException) {
                future.cancel(true);
                throw new RuntimeException("재생성 타임아웃 (10s)", e.getCause());
            }
            throw new RuntimeException("재생성 실패: " + e.getCause().getMessage(), e.getCause());
        }
    }
}
