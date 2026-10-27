package com.backtoback.judge.judging.client;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.config.Judge0Properties;
import com.backtoback.judge.judging.dto.request.Judge0ExecutionCreateRequest;
import com.backtoback.judge.judging.dto.request.JudgeExecutionCreateRequest;
import com.backtoback.judge.judging.dto.response.Judge0ExecutionCreateResponse;
import com.backtoback.judge.judging.dto.response.Judge0ExecutionResultResponse;
import com.backtoback.judge.judging.service.JudgeClient;
import com.backtoback.judge.judging.service.JudgeLanguageService;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionCreateResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;

import lombok.RequiredArgsConstructor;

/** Judge0에 실행을 접수하고 실행 결과를 조회하는 외부 연동을 담당한다. */
@Service
@RequiredArgsConstructor
public class Judge0Client implements JudgeClient {
    private static final Logger LOG = LoggerFactory.getLogger(Judge0Client.class);
    // judge0의 결과 조회 시 요청할 항목 지정
    private static final String RESULT_FIELDS
        = "token,status,stdout,stderr,compile_output,message,time,memory,exit_code,exit_signal";
    private final RestClient client;
    private final Judge0Properties properties;
    private final JudgeLanguageService languages;

    /** {@inheritDoc} */
    @Override
    public JudgeExecutionCreateResponse create(JudgeExecutionCreateRequest request) {
        // 주소 미설정을 언어 미지원으로 오인하지 않도록 설정 검사 후 실행 환경을 조회한다.
        return call(() -> {
            // 고정 환경이 없으면 캐시된 최신 환경을 사용하고, 있으면 ID·이름·버전을 검증해 재사용한다.
            var runtime = languages.requireRuntime(request.language(), request.fixedRuntime());
            // 내부 요청을 Judge0의 실행 생성 요청 형식으로 변환한다.
            // 기대값은 보내지 않는다. 함수 반환값 비교는 Judge 서비스가 담당한다.
            Judge0ExecutionCreateRequest body
                = new Judge0ExecutionCreateRequest(
                    runtime.judge0LanguageId(),
                    runtime.compilerOptions(),
                    // 코드와 표준 입력은 UTF-8 기반 Base64로 인코딩한다.
                    encode(request.sourceCode()),
                    encode(request.stdin()),
                    // 시간은 밀리초에서 초로 변환한다. 예: 2000ms → 2.000초. 메모리는 KiB 그대로 보낸다.
                    BigDecimal.valueOf(request.cpuTimeMs(), 3),
                    BigDecimal.valueOf(request.wallTimeMs(), 3),
                    request.memoryKiB(),
                    // 실행 코드가 네트워크를 사용하지 못하도록 요청한다.
                    false
                );
            // base64_encoded=true는 인코딩 형식을 알리고, wait=false는 완료를 기다리지 않고 접수 응답을 받는다.
            var response
                = client
                    .post()
                    .uri("/submissions?base64_encoded=true&wait=false")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Judge0ExecutionCreateResponse.class);
            // HTTP 상태와 본문을 함께 확인한다. 정상 접수는 201과 유효한 조회용 토큰이 있어야 한다.
            Judge0ExecutionCreateResponse result = response.getBody();
            if (response.getStatusCode().value() != 201 || result == null || !validToken(result.token())) {
                throw responseFailure(response.getStatusCode().value());
            }
            // 호출자가 결과를 조회할 토큰과 재채점 시 기록할 실제 실행 환경을 반환한다.
            // 이 메서드에서는 실행 완료를 조회하거나 DB에 저장하지 않는다.
            return new JudgeExecutionCreateResponse(result.token(), runtime);
        });
    }

    /** {@inheritDoc} */
    @Override
    public JudgeExecutionResultResponse find(String token) {
        if (!validToken(token)) {
            throw new IllegalArgumentException("A valid execution token is required");
        }
        return call(() -> {
            var response
                = client
                    .get()
                    .uri(
                        builder -> builder
                            .path("/submissions/{token}")
                            .queryParam("base64_encoded", true)
                            .queryParam("fields", RESULT_FIELDS)
                            .build(token)
                    )
                    .retrieve()
                    .toEntity(Judge0ExecutionResultResponse.class);
            Judge0ExecutionResultResponse result = response.getBody();
            if (response.getStatusCode().value() != 200 || result == null || !token.equals(result.token())
                || result.status() == null || result.status().id() == null || result.status().id() < 1
                || result.status().id() > 14) {
                throw responseFailure(response.getStatusCode().value());
            }
            int statusId = result.status().id();
            State state = State.fromStatusId(statusId);
            return new JudgeExecutionResultResponse(
                state,
                statusId,
                decode(result.stdout()),
                decode(result.stderr()),
                decode(result.compileOutput()),
                decode(result.message()),
                result.time(),
                result.memory(),
                result.exitCode(),
                result.exitSignal()
            );
        });
    }

    /**
     * HTTP 요청을 한 번만 실행하고 안전한 실패 정보만 상위 실행기에 전달한다.
     *
     * @param <T> 응답 타입
     * @param action 실행할 요청
     * @return 외부 요청 결과
     * @throws JudgeException {@code JUDGE0_CONNECTION_FAILED}: 연결 실패 또는 통신 시간 초과가 발생한 경우
     *     또는 {@code JUDGE0_RESPONSE_FAILED}: HTTP 오류 또는 올바르지 않은 응답을 받은 경우
     *     또는 {@code JUDGE0_CONFIGURATION_INVALID}: 서버 주소가 설정되지 않은 경우
     */
    private <T> T call(Supplier<T> action) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            LOG.warn("Judge0 호출 설정 오류: code=JUDGE0_CONFIGURATION_INVALID");
            throw new JudgeException(JudgeErrorCode.JUDGE0_CONFIGURATION_INVALID);
        }
        try {
            return action.get();
        } catch (ResourceAccessException exception) {
            LOG.warn("Judge0 연결 실패: exception={}", exception.getClass().getSimpleName());
            throw new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED);
        } catch (RestClientResponseException exception) {
            throw responseFailure(exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw responseFailure(null);
        }
    }

    /**
     * 원본 코드나 외부 응답 본문 없이 응답 오류를 기록하고 예외를 생성한다.
     *
     * @param status 외부 HTTP 상태; 확인할 수 없으면 null
     * @return 상위 실행기에 전달할 응답 오류 예외
     */
    private JudgeException responseFailure(Integer status) {
        LOG.warn("Judge0 응답 오류: code=JUDGE0_RESPONSE_FAILED, status={}", status);
        return new JudgeException(JudgeErrorCode.JUDGE0_RESPONSE_FAILED, status);
    }

    /**
     * 조회 경로에 넣을 수 있는 불투명 token인지 확인한다.
     *
     * @param token Judge0 실행 식별자
     * @return 비어 있지 않은 안전한 token인지 여부
     */
    private boolean validToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{1,128}");
    }

    /**
     * UTF-8 코드·입력을 Base64로 변환한다.
     *
     * @param text 전송할 문자열; null 허용
     * @return Base64 문자열 또는 null
     */
    private String encode(String text) {
        return text == null ? null : Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Judge0의 줄바꿈이 포함된 Base64 출력을 UTF-8 문자열로 복원한다.
     *
     * @param text Base64 응답; null 허용
     * @return 복원한 출력 또는 null
     * @throws JudgeException {@code JUDGE0_RESPONSE_FAILED}: 응답이 올바른 Base64가 아닌 경우
     */
    private String decode(String text) {
        if (text == null) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(text.replace("\n", "").replace("\r", ""));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw responseFailure(null);
        }
    }
}
