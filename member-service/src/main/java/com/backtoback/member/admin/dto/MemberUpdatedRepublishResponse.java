package com.backtoback.member.admin.dto;

/**
 * 회원 정보 이벤트 재발행 응답 (API 명세서 1.6.4). 기록한 {@code MemberUpdated} 수.
 */
public record MemberUpdatedRepublishResponse(long publishedCount) {

}
