package com.backtoback.study.membership.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.client.MemberStatusClient;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 생성·가입·힌트 요청 전에 정지 회원을 거절한다 (API 명세서 5.2.1, 5.2.2, 6.1.1).
 * 회원 복제본을 우선 쓰고 복제본이 없을 때만 member 상태를 조회한다 (API 명세서 1.7.1). 조회 결과로 복제본을 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MemberStatusGuard {

    private final MemberReplicaRepository memberReplicaRepository;
    private final MemberStatusClient memberStatusClient;

    /**
     * 쓰기 요청을 받아도 되는 회원인지 확인한다. member 원격 조회가 있으므로 저장 트랜잭션보다 먼저 호출한다.
     *
     * @throws StudyException {@code MEMBER_SUSPENDED}: 정지 회원,
     *     {@code AUTH_ACCESS_DENIED}: 복제본이 없고 조회 결과 탈퇴 회원,
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}: 복제본이 없고 상태 조회 실패
     */
    public void requireNotSuspended(Long memberId) {
        Optional<MemberReplica> replica = memberReplicaRepository.findByMemberId(memberId);
        if (replica.isPresent()) {
            // 탈퇴 복제본은 기존 동작대로 통과시킨다. 탈퇴 처리는 회원 탈퇴 이벤트가 가입을 정리한다.
            if (replica.get().getMemberStatus() == MemberStatus.SUSPENDED) {
                throw new StudyException(StudyErrorCode.MEMBER_SUSPENDED);
            }
            return;
        }

        MemberStatus status = memberStatusClient.fetchStatus(memberId);
        if (status == MemberStatus.SUSPENDED) {
            throw new StudyException(StudyErrorCode.MEMBER_SUSPENDED);
        }
        if (status == MemberStatus.WITHDRAWN) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
    }
}
