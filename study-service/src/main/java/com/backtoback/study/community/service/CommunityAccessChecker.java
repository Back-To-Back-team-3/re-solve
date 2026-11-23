package com.backtoback.study.community.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMembershipRepository;

import lombok.RequiredArgsConstructor;

/**
 * 커뮤니티 요청자가 게시글·댓글을 다룰 수 있는 구성원인지 확인한다(정책 및 상태 §7 커뮤니티).
 * 트랜잭션을 열지 않으므로 호출하는 서비스의 트랜잭션 안에서 사용한다.
 */
@Component
@RequiredArgsConstructor
public class CommunityAccessChecker {

    private final StudyMembershipRepository membershipRepository;

    /**
     * 요청자의 {@code APPROVED} 가입을 반환한다. 탈퇴·강퇴·대기 중이면 조회 권한도 없다.
     *
     * @throws StudyException {@code AUTH_ACCESS_DENIED}: {@code APPROVED} 구성원이 아님
     */
    public StudyMembership requireApprovedMember(Long studyId, Long memberId) {
        return membershipRepository
            .findByStudyIdAndMemberIdAndStatusIn(studyId, memberId, List.of(MembershipStatus.APPROVED))
            .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));
    }

    /** 공지 지정·상단 고정·숨김을 할 수 있는 운영진({@code LEADER}·{@code MANAGER})인지 판단한다. */
    public boolean isOperator(StudyMembership membership) {
        return membership.getRole() == MembershipRole.LEADER || membership.getRole() == MembershipRole.MANAGER;
    }
}
