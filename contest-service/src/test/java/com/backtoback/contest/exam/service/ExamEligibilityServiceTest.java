package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.contest.exam.repository.StudyMembershipReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/**
 * 신규 시험 등록의 스터디 구성원 판정을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ExamEligibilityServiceTest {
    @Mock
    private StudyMembershipReplicaRepository memberships;

    @InjectMocks
    private ExamEligibilityService eligibility;

    @Test
    @DisplayName("활성 구성원은 신규 시험 등록 자격을 통과한다")
    void allowsApprovedMember() {
        given(memberships.isActive(1L, 2L)).willReturn(true);
        assertThatCode(() -> eligibility.requireApprovedMembership(1L, 2L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("활성 구성원 복제본이 없으면 신규 시험 등록을 거절한다")
    void rejectsMissingOrInactiveMember() {
        given(memberships.isActive(1L, 2L)).willReturn(false);
        assertThatThrownBy(() -> eligibility.requireApprovedMembership(1L, 2L))
            .isInstanceOf(ContestException.class)
            .extracting("errorCode")
            .isEqualTo(ContestErrorCode.EXAM_NOT_ELIGIBLE);
    }
}
