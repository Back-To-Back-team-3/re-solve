package com.backtoback.study.membership.dto;

import java.util.List;

/**
 * 회원의 스터디 가입 목록 조회 응답 (API 명세서 §5.7.2).
 * member-service {@code WithdrawalCheckClient}가 {@code memberId}를 String으로 역직렬화하므로 Long이 아닌 String을 사용한다.
 */
public record MembershipListResponse(String memberId, List<MembershipItemResponse> items) {

}
