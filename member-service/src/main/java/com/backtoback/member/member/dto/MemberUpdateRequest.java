package com.backtoback.member.member.dto;

/**
 * 내 정보 수정 요청 (API 명세서 1.2.2). 바꿀 필드만 보낸다. 닉네임 형식 오류는 {@code MEMBER_NICKNAME_INVALID}로 응답하도록
 * Bean Validation 대신 서비스에서 확인한다.
 */
public record MemberUpdateRequest(String nickname) {

}
