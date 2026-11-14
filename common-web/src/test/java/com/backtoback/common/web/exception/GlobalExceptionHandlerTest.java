package com.backtoback.common.web.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.ErrorCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 MVC 예외 선택과 공통 오류 JSON·HTTP 헤더의 연결을 서버 없이 검증한다.
 * 서비스별 컴포넌트 스캔·보안 필터·운영 Jackson 설정은 포함하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 실패 구조와 필드별 사유</li>
 * <li>API 명세서 v1.1 / §1.5.1 진단 시작: 재응시 제한의 Retry-After 전달</li>
 * <li>정책 및 상태 v1.0 / §9.4 운영·보안 정책: 요청 본문·토큰의 로그 미기록</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {
    // 응답과 로그에 포함되어서는 안 되는 요청·예외 메시지 표본
    private static final String SECRET = "private-request-token";

    private final ObjectMapper mapper = new ObjectMapper();
    private final TestController controller = new TestController();
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    /**
     * 공통 처리기와 실제 Bean Validation을 등록하고 JSON 변환기를 명시한다.
     * 고정 검증 메시지를 사용하므로 EL 구현체 없이 동작하는 보간기를 선택한다.
     */
    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new ParameterMessageInterpolator());
        validator.afterPropertiesSet();
        mockMvc
            = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    /**
     * 테스트별로 생성한 검증 팩토리 자원을 해제한다.
     */
    @AfterEach
    void tearDown() {
        validator.close();
    }

    /**
     * 429 외에도 재시도 헤더가 필요한 409 도메인 오류를 사용한다.
     * 예외 생성 후 원본 헤더를 변경해도 최초 대기 시간이 응답에 유지되는지 확인한다.
     *
     * @throws Exception MVC 요청 처리 또는 응답 읽기에 실패한 경우
     */
    @Test
    @DisplayName("도메인 오류의 코드·상태와 복사된 Retry-After 헤더를 전달한다")
    void preservesDomainErrorAndRetryAfter() throws Exception {
        // given
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        controller.failure = new TestBusinessException(TestErrorCode.COOLDOWN, headers);
        headers.set(HttpHeaders.RETRY_AFTER, "999");

        // when
        var response = mockMvc.perform(get("/failure")).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(json).isEqualTo(mapper.readTree("""
            {"success":false,"error":{"code":"COOLDOWN","message":"재시도까지 기다려 주세요.","details":[]}}
            """));
    }

    /**
     * 빈 필드가 포함된 JSON을 실제 요청 객체로 검증해 내부 예외 원문과 필드 사유를 구분한다.
     *
     * @throws Exception MVC 요청 처리 또는 응답 읽기에 실패한 경우
     */
    @Test
    @DisplayName("요청 객체 검증 실패는 400과 필드별 사유를 반환한다")
    void returnsBodyValidationDetails() throws Exception {
        // given
        var request = post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}");

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").get("code").asString()).isEqualTo("COMMON_INVALID_REQUEST");
        assertThat(json.get("error").get("details")).isEqualTo(mapper.readTree("""
            [{"field":"name","reason":"이름은 필수입니다."}]
            """));
    }

    /**
     * Java 변수명과 다른 HTTP 파라미터 별칭을 사용해 입력 항목 이름의 선택을 검증한다.
     *
     * @throws Exception MVC 요청 처리 또는 응답 읽기에 실패한 경우
     */
    @Test
    @DisplayName("메서드 입력 검증 실패는 명시된 쿼리 이름과 사유를 반환한다")
    void returnsParameterValidationDetails() throws Exception {
        // given
        var request = get("/page").param("page-size", "0");

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(json.get("error").get("code").asString()).isEqualTo("COMMON_INVALID_REQUEST");
        assertThat(json.get("error").get("details")).isEqualTo(mapper.readTree("""
            [{"field":"page-size","reason":"크기는 1 이상이어야 합니다."}]
            """));
    }

    /**
     * 누락·빈 값·숫자가 아닌 값을 보내 서로 다른 MVC 예외가 같은 인증 실패로 연결되는지 확인한다.
     *
     * @param headerValue 사용자 식별 헤더 값; null이면 헤더 생략
     * @throws Exception MVC 요청 처리 또는 응답 읽기에 실패한 경우
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "not-a-number")
    @DisplayName("사용자 식별 헤더가 없거나 숫자로 변환되지 않으면 401을 반환한다")
    void rejectsInvalidUserHeader(String headerValue) throws Exception {
        // given
        var request = get("/user");
        if (headerValue != null) {
            request.header("X-User-Id", headerValue);
        }

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(json.get("error").get("code").asString()).isEqualTo("AUTH_TOKEN_INVALID");
    }

    /**
     * 사용자 헤더와 이름만 같은 쿼리와 일반 필수 헤더를 사용해 인증 오류의 적용 범위를 확인한다.
     *
     * @param uri 잘못된 쿼리 또는 필수 일반 헤더가 없는 요청 경로
     * @throws Exception MVC 요청 처리 또는 응답 읽기에 실패한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "/query?X-User-Id=wrong",
            "/required"
        }
    )
    @DisplayName("일반 입력 오류는 사용자 인증 실패로 바꾸지 않고 400을 반환한다")
    void keepsOrdinaryInputErrorsAsBadRequest(String uri) throws Exception {
        // given
        var request = get(uri);

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(json.get("error").get("code").asString()).isEqualTo("COMMON_INVALID_REQUEST");
    }

    /**
     * 입력 검증과 같은 예외 타입으로 발생하는 반환값 검증을 사용해 서버 책임의 실패를 구분한다.
     *
     * @throws Exception MVC 요청 처리 또는 응답 읽기에 실패한 경우
     */
    @Test
    @DisplayName("반환값 검증 실패는 검증 상세를 숨기고 500을 반환한다")
    void hidesReturnValueValidationDetails() throws Exception {
        // given
        var request = get("/return-value");

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(json.get("error").get("code").asString()).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        assertThat(json.get("error").get("details")).isEqualTo(mapper.readTree("[]"));
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRET);
    }

    /**
     * GET 전용 경로에 POST를 보내 프레임워크의 오류 상태와 Allow 헤더가 함께 보존되는지 확인한다.
     *
     * @throws Exception MVC 요청 처리 또는 응답 읽기에 실패한 경우
     */
    @Test
    @DisplayName("Spring 표준 예외는 405 상태와 Allow 헤더를 유지한다")
    void preservesFrameworkStatusAndHeaders() throws Exception {
        // given
        var request = post("/page");

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getHeader(HttpHeaders.ALLOW)).contains("GET");
        assertThat(json.get("error").get("code").asString()).isEqualTo("COMMON_INVALID_REQUEST");
    }

    /**
     * 일반 서버 오류와 타입 변환 설정 오류를 사용해 상속 관계에 따른 처리기 선택도 확인한다.
     * 원인 예외의 메시지와 거절된 값이 응답·로그에 복사되지 않아야 한다.
     *
     * @param failure 민감한 메시지 또는 값을 포함한 서버 예외
     * @throws Exception MVC 요청 처리 또는 응답 읽기에 실패한 경우
     */
    @ParameterizedTest
    @MethodSource("serverFailures")
    @DisplayName("서버 오류는 500으로 처리하고 응답과 로그에서 민감한 메시지를 제외한다")
    void excludesSecretsFromResponseAndLog(RuntimeException failure) throws Exception {
        // given
        controller.failure = failure;
        var logger = (Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            var response = mockMvc.perform(get("/failure")).andReturn().getResponse();
            var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

            // then
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(json.get("error").get("code").asString()).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRET);
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains(failure.getClass().getName());
            });
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 서버 예외의 종류를 바꾸되 메시지·원인·변환 대상 값에 같은 비밀값을 포함한다.
     *
     * @return 일반 서버 예외와 타입 변환 설정 예외의 표본
     */
    private static Stream<RuntimeException> serverFailures() {
        return Stream
            .of(
                new IllegalStateException(SECRET, new IllegalArgumentException(SECRET)),
                new ConversionNotSupportedException(SECRET, Long.class, new IllegalStateException(SECRET))
            );
    }

    /**
     * 실제 도메인 코드 없이 서비스별 오류 코드의 확장 방식을 재현한다.
     */
    private enum TestErrorCode implements ErrorCode {
        // 재시도 대기 시간이 남은 도메인 충돌
        COOLDOWN;

        /**
         * 테스트 도메인의 오류 식별자를 제공한다.
         *
         * @return enum 상수 이름
         */
        @Override
        public String getCode() {
            return name();
        }

        /**
         * 재시도 대기 상태에 대응하는 응답 상태를 제공한다.
         *
         * @return 409 충돌 상태
         */
        @Override
        public HttpStatus getStatus() {
            return HttpStatus.CONFLICT;
        }

        /**
         * 공개 가능한 테스트 안내를 제공한다.
         *
         * @return 재시도 대기 안내
         */
        @Override
        public String getMessage() {
            return "재시도까지 기다려 주세요.";
        }
    }

    /**
     * 서비스가 오류 코드와 부가 응답 헤더를 공통 예외에 전달하는 상황을 재현한다.
     */
    private static final class TestBusinessException extends BusinessException {
        /**
         * 도메인이 정한 오류와 대기 시간을 기반 생성자로 전달한다.
         *
         * @param code 테스트 도메인 오류
         * @param headers 응답에 포함할 헤더
         */
        private TestBusinessException(ErrorCode code, HttpHeaders headers) {
            super(code, headers);
        }
    }

    /**
     * 요청 객체의 필드 검증을 위한 최소 입력이다.
     *
     * @param name 비어 있지 않아야 하는 이름
     */
    record TestRequest(@NotBlank(message = "이름은 필수입니다.") String name) {
    }

    /**
     * 도메인 로직 없이 MVC가 공통 처리기에 전달하는 예외만 발생시킨다.
     */
    @RestController
    static class TestController {
        private RuntimeException failure;

        /**
         * 테스트에서 준비한 서버 또는 비즈니스 예외를 발생시킨다.
         *
         * @throws RuntimeException 각 테스트가 지정한 예외
         */
        @GetMapping("/failure")
        void fail() {
            throw failure;
        }

        /**
         * 요청 객체 필드 검증을 MVC에 맡긴다.
         *
         * @param request 필드 제약을 가진 테스트 입력
         */
        @PostMapping("/body")
        void body(@Valid @RequestBody TestRequest request) {}

        /**
         * HTTP 입력 이름이 Java 이름과 다른 파라미터 검증을 발생시킨다.
         *
         * @param size 1 이상이어야 하는 페이지 크기
         */
        @GetMapping("/page")
        void page(
            @RequestParam("page-size")
            @Min(
                value = 1,
                message = "크기는 1 이상이어야 합니다."
            ) int size
        ) {}

        /**
         * 사용자 식별 헤더의 누락과 숫자 변환 실패를 발생시킨다.
         *
         * @param memberId 필수 사용자 식별자
         */
        @GetMapping("/user")
        void user(@RequestHeader("X-User-Id") Long memberId) {}

        /**
         * 인증 헤더와 같은 이름의 일반 쿼리는 별개 입력임을 재현한다.
         *
         * @param value 숫자로 변환할 일반 쿼리 값
         */
        @GetMapping("/query")
        void query(@RequestParam("X-User-Id") Long value) {}

        /**
         * 인증과 관계없는 필수 헤더의 누락을 발생시킨다.
         *
         * @param key 필수 멱등키
         */
        @GetMapping("/required")
        void required(@RequestHeader("Idempotency-Key") String key) {}

        /**
         * 요청자에게 노출하면 안 되는 반환값 검증 사유를 발생시킨다.
         *
         * @return 제약을 위반하는 0
         */
        @GetMapping("/return-value")
        @Min(
            value = 1,
            message = SECRET
        )
        int invalidReturnValue() {
            return 0;
        }
    }
}
