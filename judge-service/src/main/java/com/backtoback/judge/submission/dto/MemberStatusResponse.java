package com.backtoback.judge.submission.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 회원 상태 조회 응답이다.
 *
 * @param memberId 회원 ID
 * @param status 상태
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberStatusResponse(String memberId, String status) {
}
