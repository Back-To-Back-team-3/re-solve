package com.backtoback.contest.exam.exception;

import com.backtoback.contest.global.error.BusinessException;
import com.backtoback.contest.global.error.ErrorCode;

/**
 * 시험 생성에 필요한 외부 도메인 조회 실패를 전달한다.
 * 오류 코드를 고정해 호출자가 다른 상태를 잘못 지정하지 않도록 한다.
 * 실제 판정은 후속 업무 서비스에서 수행하며 개발 stub에서도 같은 예외를 사용한다.
 */
public class ContestDependencyUnavailableException extends BusinessException {
    /**
     * 이 상황에 해당하는 명세 오류 코드를 설정한다.
     */
    public ContestDependencyUnavailableException() {
        super(ErrorCode.CONTEST_DEPENDENCY_UNAVAILABLE);
    }
}
