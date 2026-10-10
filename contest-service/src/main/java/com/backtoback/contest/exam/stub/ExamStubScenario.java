package com.backtoback.contest.exam.stub;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.backtoback.contest.exam.exception.ContestDependencyUnavailableException;
import com.backtoback.contest.exam.exception.ContestOperationBlockedException;
import com.backtoback.contest.exam.exception.ExamAlreadyFinishedException;
import com.backtoback.contest.exam.exception.ExamCanceledException;
import com.backtoback.contest.exam.exception.ExamClosedException;
import com.backtoback.contest.exam.exception.ExamNotEligibleException;
import com.backtoback.contest.exam.exception.ExamNotFoundException;
import com.backtoback.contest.exam.exception.ExamNotRegisteredException;
import com.backtoback.contest.exam.exception.ExamNotStartedException;
import com.backtoback.contest.exam.exception.ExamProblemNotAvailableException;
import com.backtoback.contest.exam.exception.ExamStudyClosedException;
import com.backtoback.contest.global.error.BusinessException;
import com.backtoback.contest.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 개발 요청의 X-Exam-Stub-Scenario 헤더에서 성공·기존 참가·명세 오류 예시를 선택한다.
 * Servlet 요청 프록시를 사용하므로 한 요청의 시나리오를 다른 요청과 공유하지 않는다.
 * 이 헤더는 공개 API 계약의 일부가 아니며 stub 프로필 외에는 사용하지 않는다.
 */
@Component
@Profile("dev & exam-stub & !prod & !production")
@RequiredArgsConstructor
public class ExamStubScenario {
    private final HttpServletRequest request;

    /**
     * SUCCESS와 EXISTING_PARTICIPANT 외 값은 명세 오류 이름으로 해석한다.
     *
     * @throws BusinessException 선택한 명세 오류 또는 알 수 없는 시나리오의 입력 오류
     */
    public void raiseIfRequested() {
        String scenario = request.getHeader("X-Exam-Stub-Scenario");
        if (scenario == null || "SUCCESS".equals(scenario) || "EXISTING_PARTICIPANT".equals(scenario)) {
            return;
        }
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(scenario);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        throw scenarioException(code);
    }

    /**
     * 시나리오의 도메인 코드는 상황별 예외로, 공통 코드는 공통 업무 예외로 변환한다.
     * 이 변환은 개발 응답 예시를 선택할 뿐 실제 업무 조건을 판정하지 않는다.
     *
     * @param code 선택한 명세 오류 코드
     * @return 해당 코드가 고정된 도메인 예외 또는 공통 예외
     */
    private BusinessException scenarioException(ErrorCode code) {
        return switch (code) {
            case EXAM_NOT_FOUND -> new ExamNotFoundException();
            case EXAM_NOT_ELIGIBLE -> new ExamNotEligibleException();
            case EXAM_NOT_REGISTERED -> new ExamNotRegisteredException();
            case EXAM_NOT_STARTED -> new ExamNotStartedException();
            case EXAM_CLOSED -> new ExamClosedException();
            case EXAM_CANCELED -> new ExamCanceledException();
            case EXAM_ALREADY_FINISHED -> new ExamAlreadyFinishedException();
            case EXAM_STUDY_CLOSED -> new ExamStudyClosedException();
            case EXAM_PROBLEM_NOT_AVAILABLE -> new ExamProblemNotAvailableException();
            case CONTEST_OPERATION_BLOCKED -> new ContestOperationBlockedException();
            case CONTEST_DEPENDENCY_UNAVAILABLE -> new ContestDependencyUnavailableException();
            default -> new BusinessException(code);
        };
    }

    /**
     * 기존 참가 반환의 200 예시를 선택했는지 확인한다. 실제 참가 존재를 조회하지 않는다.
     *
     * @return 기존 참가 시나리오면 true
     */
    public boolean isExistingParticipant() {
        return "EXISTING_PARTICIPANT".equals(request.getHeader("X-Exam-Stub-Scenario"));
    }
}
