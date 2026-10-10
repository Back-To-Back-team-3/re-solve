package com.backtoback.contest.exam.stub;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.backtoback.contest.global.error.ErrorCode;
import com.backtoback.contest.global.error.ExamApiException;

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
     * @throws ExamApiException 선택한 명세 오류 또는 알 수 없는 시나리오의 입력 오류
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
            throw new ExamApiException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        throw new ExamApiException(code);
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
