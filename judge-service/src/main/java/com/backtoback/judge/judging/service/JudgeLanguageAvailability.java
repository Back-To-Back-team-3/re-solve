package com.backtoback.judge.judging.service;

import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;

/**
 * 서비스에서 선택한 언어 실행 환경과 사용 허용 여부.
 *
 * @param language 지원 언어
 * @param runtime 선택한 실행 환경; 후보가 없으면 null
 * @param enabled 활성화 설정과 실행 환경 존재 여부를 모두 충족하는지 여부
 */
public record JudgeLanguageAvailability(JudgeLanguage language, JudgeRuntime runtime, boolean enabled) {
}
