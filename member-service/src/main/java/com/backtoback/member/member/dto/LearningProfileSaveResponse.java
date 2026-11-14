package com.backtoback.member.member.dto;

/**
 * 학습 프로필 저장 응답 (API 명세서 1.3.2).
 */
public record LearningProfileSaveResponse(String profileVersion) {

    public static LearningProfileSaveResponse of(long profileVersion) {
        return new LearningProfileSaveResponse(String.valueOf(profileVersion));
    }
}
