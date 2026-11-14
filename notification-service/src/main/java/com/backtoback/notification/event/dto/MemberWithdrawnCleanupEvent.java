package com.backtoback.notification.event.dto;

/**
 * 회원 탈퇴({@code MemberWithdrawn}) 트랜잭션 커밋 이후 해당 회원의 활성 SSE 연결을 종료하기 위한 내부 애플리케이션 이벤트 (이벤트 계약서 4.1 M3, 5.1).
 *
 * @param memberId 탈퇴한 회원 ID
 */
public record MemberWithdrawnCleanupEvent(Long memberId) {
}
