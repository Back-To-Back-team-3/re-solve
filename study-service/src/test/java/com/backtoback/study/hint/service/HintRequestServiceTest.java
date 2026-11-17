package com.backtoback.study.hint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.client.HintContextResponse;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.dto.HintAcceptResult;
import com.backtoback.study.membership.service.MemberStatusGuard;

/**
 * 힌트 요청의 회원 정지 확인 순서를 검증한다 (API 명세서 6.1.1 검증 순서 ⓪).
 */
@ExtendWith(MockitoExtension.class)
class HintRequestServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long PROBLEM_ID = 101L;

    @Mock
    private MemberStatusGuard memberStatusGuard;
    @Mock
    private HintValidationService hintValidationService;
    @Mock
    private HintTransactionalService hintTransactionalService;

    @InjectMocks
    private HintRequestService hintRequestService;

    @Test
    @DisplayName("정지 회원이면 문제 문맥 조회와 접수를 하지 않고 MEMBER_SUSPENDED로 거절한다")
    void suspendedMember_rejectedBeforeValidation() {
        willThrow(new StudyException(StudyErrorCode.MEMBER_SUSPENDED))
            .given(memberStatusGuard)
            .requireNotSuspended(MEMBER_ID);

        assertThatThrownBy(() -> hintRequestService.request(MEMBER_ID, PROBLEM_ID, 1))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.MEMBER_SUSPENDED)
            );
        then(hintValidationService).should(never()).validate(anyLong(), anyLong());
        then(hintTransactionalService).should(never()).accept(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("정지 확인을 통과하면 정지 확인, 문제 문맥 검증, 접수 순서로 처리한다")
    void activeMember_checksStatusFirst() {
        HintContextResponse context = new HintContextResponse(true, 11L, "제목", "본문", List.of("구현"));
        HintAcceptResult accepted = new HintAcceptResult(300L, HintRequestStatus.REQUESTED, null, 1);
        given(hintValidationService.validate(MEMBER_ID, PROBLEM_ID)).willReturn(context);
        given(hintTransactionalService.accept(MEMBER_ID, PROBLEM_ID, context, 1)).willReturn(accepted);

        HintAcceptResult result = hintRequestService.request(MEMBER_ID, PROBLEM_ID, 1);

        assertThat(result).isEqualTo(accepted);
        InOrder order = inOrder(memberStatusGuard, hintValidationService, hintTransactionalService);
        order.verify(memberStatusGuard).requireNotSuspended(MEMBER_ID);
        order.verify(hintValidationService).validate(MEMBER_ID, PROBLEM_ID);
        order.verify(hintTransactionalService).accept(MEMBER_ID, PROBLEM_ID, context, 1);
    }
}
