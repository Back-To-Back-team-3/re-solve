package com.backtoback.study.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.exception.ErrorCode;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.member.client.MemberStatusClient;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * 복제본 우선·상태 조회 보조의 정지 판정을 검증한다 (API 명세서 5.2.1, 5.2.2, 6.1.1).
 */
@ExtendWith(MockitoExtension.class)
class MemberStatusGuardTest {

    private static final Long MEMBER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Mock
    private MemberReplicaRepository memberReplicaRepository;
    @Mock
    private MemberStatusClient memberStatusClient;

    @InjectMocks
    private MemberStatusGuard memberStatusGuard;

    @Test
    @DisplayName("복제본이 정지 상태이면 member를 조회하지 않고 MEMBER_SUSPENDED로 거절한다")
    void suspendedReplica_throwsMemberSuspended() {
        givenReplica(MemberStatus.SUSPENDED);

        assertRejectedWith(StudyErrorCode.MEMBER_SUSPENDED);
        then(memberStatusClient).should(never()).fetchStatus(anyLong());
    }

    @ParameterizedTest
    @EnumSource(
        value = MemberStatus.class,
        names = {
            "ACTIVE",
            "WITHDRAWN"
        }
    )
    @DisplayName("복제본이 활성·탈퇴 상태이면 member를 조회하지 않고 통과한다")
    void nonSuspendedReplica_passes(MemberStatus status) {
        givenReplica(status);

        assertThatCode(() -> memberStatusGuard.requireNotSuspended(MEMBER_ID)).doesNotThrowAnyException();
        then(memberStatusClient).should(never()).fetchStatus(anyLong());
    }

    @Test
    @DisplayName("복제본이 없고 member 조회 결과가 활성이면 통과한다")
    void noReplica_active_passes() {
        givenNoReplica(MemberStatus.ACTIVE);

        assertThatCode(() -> memberStatusGuard.requireNotSuspended(MEMBER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("복제본이 없고 member 조회 결과가 정지이면 MEMBER_SUSPENDED로 거절한다")
    void noReplica_suspended_throwsMemberSuspended() {
        givenNoReplica(MemberStatus.SUSPENDED);

        assertRejectedWith(StudyErrorCode.MEMBER_SUSPENDED);
    }

    @Test
    @DisplayName("복제본이 없고 member 조회 결과가 탈퇴이면 AUTH_ACCESS_DENIED로 거절한다")
    void noReplica_withdrawn_throwsAccessDenied() {
        givenNoReplica(MemberStatus.WITHDRAWN);

        assertRejectedWith(CommonErrorCode.AUTH_ACCESS_DENIED);
    }

    @Test
    @DisplayName("복제본이 없고 member 조회가 실패하면 COMMON_DEPENDENCY_UNAVAILABLE로 거절한다")
    void noReplica_lookupFails_throwsDependencyUnavailable() {
        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(memberStatusClient.fetchStatus(MEMBER_ID))
            .willThrow(new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));

        assertRejectedWith(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }

    private void givenReplica(MemberStatus status) {
        MemberReplica replica = MemberReplica.sync(MEMBER_ID, "user7", null, status, 1L, NOW);
        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(replica));
    }

    private void givenNoReplica(MemberStatus remoteStatus) {
        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(memberStatusClient.fetchStatus(MEMBER_ID)).willReturn(remoteStatus);
    }

    private void assertRejectedWith(ErrorCode errorCode) {
        assertThatThrownBy(() -> memberStatusGuard.requireNotSuspended(MEMBER_ID))
            .isInstanceOfSatisfying(StudyException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }
}
