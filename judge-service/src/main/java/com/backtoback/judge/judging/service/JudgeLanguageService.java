package com.backtoback.judge.judging.service;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.client.Judge0LanguageClient;
import com.backtoback.judge.judging.config.Judge0Properties;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.dto.response.Judge0LanguageResponse;

import lombok.RequiredArgsConstructor;

/**
 * 실행 환경 목록과 언어별 선택 결과를 캐시하고 고정 실행 환경의 재사용 가능 여부를 판단한다.
 */
@Service
@RequiredArgsConstructor
public class JudgeLanguageService {
    private final Judge0Properties properties;
    private final Judge0LanguageClient languageClient;
    private List<Judge0LanguageResponse> cachedLanguages;
    private Map<JudgeLanguage, JudgeRuntime> cachedLatestRuntimes = Map.of();

    /**
     * Judge0에서 조회한 런타임과 서비스의 활성화 정책을 함께 반환한다.
     * 주소가 없거나 지원 후보가 없으면 해당 언어를 비활성으로 표시한다.
     *
     * @return 내부 Judge0 ID를 노출하지 않는 언어 목록
     * @throws JudgeException {@code JUDGE0_CONNECTION_FAILED}: 연결 실패 또는 통신 시간 초과가 발생한 경우
     *     또는 {@code JUDGE0_RESPONSE_FAILED}: HTTP 오류 또는 올바르지 않은 응답을 받은 경우
     */
    public List<JudgeLanguageAvailability> findAll() {
        availableLanguages();
        return Arrays.stream(JudgeLanguage.values()).map(language -> {
            JudgeRuntime runtime = cachedLatestRuntimes.get(language);
            return new JudgeLanguageAvailability(language, runtime, enabled(language) && runtime != null);
        }).toList();
    }

    /**
     * 고정 환경이 없으면 캐시된 최신 실행 환경을 반환하고, 있으면 최초 조회한 Judge0 목록과 일치하는지 검증한다.
     * 고정 환경의 ID·이름·버전이 달라지면 대체하지 않고 거부하며 컴파일 옵션도 그대로 유지한다.
     *
     * @param language java, python3 또는 cpp
     * @param fixedRuntime 사용할 고정 실행 환경; null이면 지원 범위의 최신 환경을 선택한다
     * @return 캐시된 최신 환경 또는 검증을 통과한 고정 환경
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 미지원 언어이거나 최신 환경 선택 시 비활성·후보 없음인 경우
     *     또는 {@code SUBMISSION_RUNTIME_UNAVAILABLE}: 고정 환경의 언어·ID·이름·버전이 다르거나 비활성인 경우
     *     또는 {@code JUDGE0_CONNECTION_FAILED}: 연결 실패 또는 통신 시간 초과가 발생한 경우
     *     또는 {@code JUDGE0_RESPONSE_FAILED}: HTTP 오류 또는 올바르지 않은 응답을 받은 경우
     */
    public JudgeRuntime requireRuntime(String language, JudgeRuntime fixedRuntime) {
        JudgeLanguage supportedLanguage = JudgeLanguage.fromCode(language);
        if (fixedRuntime != null) {
            // 고정 환경은 최신 버전이나 같은 버전의 다른 ID로 대체하지 않는다.
            if (!language.equals(fixedRuntime.language()) || fixedRuntime.judge0LanguageId() <= 0
                || !enabled(supportedLanguage)) {
                throw new JudgeException(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE);
            }
            // 최초 조회한 목록에서 고정된 ID·이름·버전을 확인한다.
            for (Judge0LanguageResponse runtime : availableLanguages()) {
                if (runtime.id() != fixedRuntime.judge0LanguageId()) {
                    continue;
                }
                var matcher = supportedLanguage.getRuntimePattern().matcher(runtime.name());
                if (!runtime.name().equals(fixedRuntime.runtimeName()) || !matcher.matches()
                    || !matcher.group(1).equals(fixedRuntime.runtimeVersion())) {
                    throw new JudgeException(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE);
                }
                return fixedRuntime;
            }
            throw new JudgeException(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE);
        }

        // 고정 환경이 없으면 최초 조회 때 선택해 둔 최신 실행 환경을 반환한다.
        if (!enabled(supportedLanguage)) {
            throw new JudgeException(JudgeErrorCode.UNSUPPORTED_LANGUAGE);
        }
        availableLanguages();
        JudgeRuntime runtime = cachedLatestRuntimes.get(supportedLanguage);
        if (runtime == null) {
            throw new JudgeException(JudgeErrorCode.UNSUPPORTED_LANGUAGE);
        }
        return runtime;
    }

    /**
     * 검증 후 명시적으로 활성화한 언어인지 확인한다.
     *
     * @param language 지원 언어
     * @return 해당 언어의 활성화 설정값
     */
    private boolean enabled(JudgeLanguage language) {
        return switch (language) {
            case JAVA -> properties.languages().java().enabled();
            case PYTHON3 -> properties.languages().python3().enabled();
            case CPP -> properties.languages().cpp().enabled();
        };
    }

    /**
     * 지원 범위 안에서 가장 높은 정식 버전의 실행 환경을 선택한다.
     * Java는 JDK·OpenJDK 17, Python은 3, C++은 GCC를 대상으로 숫자 버전만 비교한다.
     * 같은 버전의 후보가 여러 개면 가장 작은 ID를 선택한다.
     *
     * @param language 지원 언어
     * @param runtimes Judge0 활성 언어 목록
     * @return 선택한 버전에서 ID가 가장 작은 후보; 후보가 없으면 null
     */
    private Judge0LanguageResponse select(JudgeLanguage language, List<Judge0LanguageResponse> runtimes) {
        // selected는 ID·이름을 가진 선택 결과, highestVersion은 그 결과의 비교용 버전이다.
        Judge0LanguageResponse selected = null;
        String[] highestVersion = null;
        for (Judge0LanguageResponse runtime : runtimes) {
            // Enum의 패턴으로 요청 언어와 지원 범위를 확인한다.
            // Java 요청이면 Java 17만 후보가 되며, Python이나 Java 21은 건너뛴다.
            var matcher = language.getRuntimePattern().matcher(runtime.name());
            // 숫자와 점으로만 구성된 정식 버전만 선택하여 시험판·알 수 없는 형식을 제외한다.
            if (!matcher.matches()) {
                continue;
            }
            // 첫 번째 캡처 그룹에는 숫자 버전만 들어 있다.
            // "Java (JDK 17.0.6)"에서 "17.0.6"을 추출한 뒤 점으로 나눈다.
            String[] version = matcher.group(1).split("\\.");
            // 첫 후보는 비교 대상이 없으므로 양수로 처리해 바로 선택한다.
            // 이후에는 현재 선택보다 높은지 숫자로 비교한다. 예를 들어 17.0.10은 17.0.9보다 높다.
            int comparison = highestVersion == null ? 1 : compareVersions(version, highestVersion);
            if (comparison > 0) {
                // 최초 후보 또는 더 높은 버전으로 선택 결과를 교체한다.
                selected = runtime;
                highestVersion = version;
            } else if (comparison == 0 && runtime.id() < selected.id()) {
                // 같은 버전이라면 목록 순서와 무관하게 가장 작은 ID를 선택한다.
                // ID는 버전의 높낮이를 나타내지 않으므로 버전이 같을 때만 비교한다.
                selected = runtime;
            }
        }
        // 후보가 없으면 null이다. 선택했다면 ID·이름을 그대로 반환해 해당 ID로 실행하도록 한다.
        return selected;
    }

    /**
     * 점으로 나눈 버전 구성요소를 숫자로 비교한다.
     * 누락된 뒷부분은 0으로 취급하여 17과 17.0을 같은 버전으로 판단한다.
     *
     * @param left 비교할 버전의 숫자 구성요소
     * @param right 기준 버전의 숫자 구성요소
     * @return 왼쪽이 높으면 양수, 같으면 0, 낮으면 음수
     */
    private int compareVersions(String[] left, String[] right) {
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            BigInteger leftPart = index < left.length ? new BigInteger(left[index]) : BigInteger.ZERO;
            BigInteger rightPart = index < right.length ? new BigInteger(right[index]) : BigInteger.ZERO;
            int comparison = leftPart.compareTo(rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /**
     * 활성 언어 목록을 처음 필요할 때 조회하고 언어별 최신 실행 환경도 함께 캐시한다.
     * 목록과 선택 결과는 프로세스가 종료될 때까지 재사용한다.
     * 동시 최초 요청도 한 번만 조회하며, 실패한 응답은 저장하지 않아 다음 요청에서 다시 조회한다.
     * 일반 조회와 재채점이 같은 캐시를 사용한다. Judge0 언어 구성을 변경하면 judge-service도 재시작한다.
     *
     * @return 검증한 활성 언어 목록; 서버 주소 미설정이면 빈 목록
     * @throws JudgeException {@code JUDGE0_CONNECTION_FAILED}: 연결 실패 또는 통신 시간 초과가 발생한 경우
     *     또는 {@code JUDGE0_RESPONSE_FAILED}: HTTP 오류 또는 올바르지 않은 응답을 받은 경우
     */
    private synchronized List<Judge0LanguageResponse> availableLanguages() {
        // synchronized로 동시 최초 요청을 기다리게 하여 목록 조회와 캐시 초기화를 한 번만 수행한다.
        // 정상 조회한 빈 목록도 캐시다. 이후에는 HTTP 조회와 최신 버전 선택을 모두 생략한다.
        if (cachedLanguages != null) {
            return cachedLanguages;
        }
        // HTTP 호출·응답 형식 검증은 전용 클라이언트에 맡긴다. 실패하면 캐시 없이 예외를 전달한다.
        List<Judge0LanguageResponse> languages = languageClient.findAll();
        // EnumMap은 JudgeLanguage를 키로 사용하며, 언어마다 최신 실행 환경 하나를 보관한다.
        var latestRuntimes = new EnumMap<JudgeLanguage, JudgeRuntime>(JudgeLanguage.class);
        for (JudgeLanguage language : JudgeLanguage.values()) {
            // 활성화 여부와 별개로 언어별 최신 후보를 한 번만 선택한다.
            // 실제 사용 허용 여부는 findAll과 requireRuntime에서 확인한다.
            Judge0LanguageResponse runtime = select(language, languages);
            // 지원 범위에 맞는 후보가 없으면 해당 언어는 Map에 넣지 않는다.
            if (runtime == null) {
                continue;
            }
            // select에서 검증한 패턴으로 숫자 버전을 추출해 실행에 필요한 정보까지 보관한다.
            var matcher = language.getRuntimePattern().matcher(runtime.name());
            // 새 Matcher이므로 matches를 실행해야 group(1)에 숫자 버전이 기록된다.
            matcher.matches();
            // 버전 추출과 객체 생성을 반복하지 않도록 ID·이름·버전·컴파일 옵션을 함께 저장한다.
            // C++은 C++17 옵션을 지정하고, 다른 언어는 별도 컴파일 옵션을 지정하지 않는다.
            latestRuntimes
                .put(
                    language,
                    new JudgeRuntime(
                        language.getCode(),
                        runtime.id(),
                        runtime.name(),
                        matcher.group(1),
                        language == JudgeLanguage.CPP ? "-std=c++17" : null
                    )
                );
        }
        // 두 결과를 모두 준비한 뒤 캐시에 저장한다. 준비 중 실패하면 다음 요청에서 다시 조회한다.
        // 최신 환경 Map도 수정할 수 없게 보관하고, 전체 목록을 저장하여 초기화 완료를 표시한다.
        cachedLatestRuntimes = Map.copyOf(latestRuntimes);
        cachedLanguages = languages;
        return cachedLanguages;
    }
}
