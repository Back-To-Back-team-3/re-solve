package com.backtoback.judge.judging.dto.response;

import java.util.List;

public record JudgeLanguagesResponse(List<Language> content) {
    public record Language(String language, String displayName, String runtimeVersion, boolean enabled) {
    }
}
