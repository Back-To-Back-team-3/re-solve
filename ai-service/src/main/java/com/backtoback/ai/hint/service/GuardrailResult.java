package com.backtoback.ai.hint.service;

public record GuardrailResult(boolean passed, String text) {

    public static GuardrailResult pass(String text) {
        return new GuardrailResult(true, text);
    }

    public static GuardrailResult fallback() {
        return new GuardrailResult(false, null);
    }
}
