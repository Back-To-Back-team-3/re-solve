package com.backtoback.member.member.service;

/**
 * {@code MemberWithdrawn} v1 업무 필드 (이벤트 계약 M3). 공통 헤더는 {@code OutboxEventWriter}가 붙인다.
 *
 * @param withdrawnAt {@code +09:00}, 마이크로초 6자리
 */
public record MemberWithdrawnPayload(String memberId, String withdrawnAt, long sourceVersion) {

}
