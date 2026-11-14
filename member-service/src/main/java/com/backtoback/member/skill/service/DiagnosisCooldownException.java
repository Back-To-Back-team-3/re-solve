package com.backtoback.member.skill.service;

import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;

import lombok.Getter;

/**
 * 재응시 쿨타임 중 진단 시작 (API 명세서 1.5.1 {@code DIAGNOSIS_COOLDOWN}). 응답에 {@code Retry-After}를 싣기 위해 남은 초를 가진다.
 */
@Getter
public class DiagnosisCooldownException extends MemberException {

    private final long retryAfterSeconds;

    public DiagnosisCooldownException(long retryAfterSeconds) {
        super(MemberErrorCode.DIAGNOSIS_COOLDOWN);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
