package com.backtoback.contest.exam.exception;

import com.backtoback.contest.global.error.BusinessException;
import com.backtoback.contest.global.error.ErrorCode;

/**
 * 종료된 스터디에 대한 시험 작업 오류다.
 * 오류 코드를 고정해 호출자가 다른 상태를 잘못 지정하지 않도록 한다.
 * 실제 판정은 후속 업무 서비스에서 수행하며 개발 stub에서도 같은 예외를 사용한다.
 * <p>기준 문서: 에러 코드 표 / Exam · Contest: 이 상황의 오류 코드와 HTTP 상태.
 */
public class ExamStudyClosedException extends BusinessException {
    /**
     * 이 상황에 해당하는 명세 오류 코드를 설정한다.
     */
    public ExamStudyClosedException() {
        super(ErrorCode.EXAM_STUDY_CLOSED);
    }
}
