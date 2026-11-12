package com.backtoback.ai.hint.client;

public class HintGeneratingConflictException extends RuntimeException {

    public HintGeneratingConflictException(String hintRequestId) {
        super("GENERATING 전이 충돌: hintRequestId=" + hintRequestId);
    }
}
