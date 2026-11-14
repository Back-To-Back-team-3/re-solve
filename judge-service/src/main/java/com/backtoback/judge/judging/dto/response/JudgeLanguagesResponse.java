package com.backtoback.judge.judging.dto.response;

import java.util.List;

import com.backtoback.judge.judging.service.JudgeLanguageAvailability;

/**
 * 지원 언어 목록 응답. (API 명세서 3.1.1)
 *
 * @param content 언어별 실행 환경 표시 정보
 */
public record JudgeLanguagesResponse(List<Language> content) {
    /**
     * 서비스의 언어 지원 결과를 공개 응답으로 변환한다.
     *
     * @param languages 서비스에서 선택한 실행 환경과 활성화 여부
     * @return 내부 Judge0 ID를 노출하지 않는 언어 목록
     */
    public static JudgeLanguagesResponse from(List<JudgeLanguageAvailability> languages) {
        return new JudgeLanguagesResponse(languages.stream().map(language -> {
            var runtime = language.runtime();
            String version
                = runtime == null ? "배포 환경 확인 전"
                    : runtime
                        .runtimeName()
                        .substring(runtime.runtimeName().indexOf('(') + 1, runtime.runtimeName().length() - 1);
            return new Language(
                language.language().getCode(),
                language.language().getDisplayName(),
                version,
                language.enabled()
            );
        }).toList());
    }

    /**
     * 언어별 실행 환경 표시 정보.
     *
     * @param language 언어 코드
     * @param displayName 언어 표시 이름
     * @param runtimeVersion 선택한 실행 환경 표시 정보
     * @param enabled 언어 사용 허용 여부
     */
    public record Language(String language, String displayName, String runtimeVersion, boolean enabled) {
    }
}
