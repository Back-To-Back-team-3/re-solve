package com.backtoback.contest.exam.exception;

import com.backtoback.contest.global.error.BusinessException;
import com.backtoback.contest.global.error.ErrorCode;

/**
 * 마감된 시험에 대한 작업 오류다.
 * 오류 코드를 고정해 호출자가 다른 상태를 잘못 지정하지 않도록 한다.
 * 실제 판정은 후속 업무 서비스에서 수행하며 개발 stub에서도 같은 예외를 사용한다.
 */
public class ExamClosedException extends BusinessException {
    /**
     * 이 상황에 해당하는 명세 오류 코드를 설정한다.
     */
    public ExamClosedException() {
        super(ErrorCode.EXAM_CLOSED);
    }
}
