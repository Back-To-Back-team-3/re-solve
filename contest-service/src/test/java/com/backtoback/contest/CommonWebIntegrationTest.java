package com.backtoback.contest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.global.config.CommonConfiguration;
import com.backtoback.contest.global.config.SecurityConfiguration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 실제 contest 공통 설정이 MVC·보안·요청 추적·HTTP 호출을 연결하는지 검증한다.
 * 테스트 컨트롤러는 검증·예외와 인증 경로만 노출하며 도메인 API를 추가하지 않는다.
 */
@SpringBootTest(
    classes = {
        ContestServiceApplication.class,
        CommonConfiguration.class,
        SecurityConfiguration.class,
        CommonWebIntegrationTest.MvcTestConfiguration.class
    },
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers
class CommonWebIntegrationTest {
    private static final String USER_ID = "member-12";
    private static final String CORRELATION_ID = "contest-request-12";
    private static final String REDIS_PASSWORD = "contest-web-test-password";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");

    @Container
    static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private ProbeController probeController;

    private MockMvc mockMvc;

    /**
     * Testcontainers의 DB와 Redis 접속값을 실제 contest 애플리케이션 컨텍스트에 주입한다.
     *
     * @param registry 컨테이너 속성을 Spring에 등록할 테스트 속성 레지스트리
     */
    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    /**
     * Spring Boot MVC 컨텍스트에 실제 보안 필터 체인을 설치한다.
     */
    @BeforeEach
    void setUp() {
        mockMvc
            = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(context.getBean(CorrelationIdFilter.class))
                .apply(springSecurity())
                .build();
    }

    /**
     * 요청 인증과 추적 ID가 다음 테스트 스레드에 남지 않도록 정리한다.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /**
     * 요청 본문 검증·비즈니스 예외·예상하지 못한 MVC 예외가 공통 오류 계약을 따르는지 확인한다.
     *
     * @throws Exception MockMvc 요청을 수행하지 못한 경우
     */
    @Test
    @DisplayName("검증·비즈니스·예상 밖 예외를 공통 오류 응답으로 변환한다")
    void mapsMvcErrorsToCommonResponses() throws Exception {
        // given: 사용자 인증 헤더가 있는 요청과 공통 처리기가 준비됐다.
        // when: 잘못된 입력, 비즈니스 예외, 예상하지 못한 예외를 각각 요청한다.
        var invalid
            = mockMvc
                .perform(
                    post("/api/v1/test/validation")
                        .header("X-User-Id", USER_ID)
                        .header("X-User-Role", "USER")
                        .contentType("application/json")
                        .content("{\"name\":\" \"}")
                )
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse();
        var business
            = mockMvc
                .perform(get("/api/v1/test/business-error").header("X-User-Id", USER_ID).header("X-User-Role", "USER"))
                .andReturn()
                .getResponse();
        var unexpected
            = mockMvc
                .perform(get("/api/v1/test/server-error").header("X-User-Id", USER_ID).header("X-User-Role", "USER"))
                .andReturn()
                .getResponse();
        // then: 공통 상태와 오류 코드를 반환하고 내부 예외 메시지는 감춘다.
        assertThat(invalid.getContentAsString()).contains("COMMON_INVALID_REQUEST");
        assertThat(business.getStatus()).isEqualTo(422);
        assertThat(business.getContentAsString()).contains("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(unexpected.getStatus()).isEqualTo(500);
        assertThat(unexpected.getContentAsString()).contains("COMMON_INTERNAL_SERVER_ERROR");
        assertThat(unexpected.getContentAsString()).doesNotContain("private server detail");
    }

    /**
     * 공개 GET·익명 인증 실패·USER 권한 부족·ADMIN 접근과 내부 헤더 무시를 확인한다.
     *
     * @throws Exception MockMvc 요청을 수행하지 못한 경우
     */
    @Test
    @DisplayName("공개·로그인·관리자·내부 요청을 설정된 권한으로 처리한다")
    void appliesContestPathAuthorization() throws Exception {
        // given: 공개·로그인·관리자·내부 경로에 필요한 역할별 요청을 준비한다.
        // when
        mockMvc.perform(get("/api/v1/contests")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/test/me")).andExpect(status().isUnauthorized());
        mockMvc
            .perform(get("/api/v1/admin/exams/test").header("X-User-Id", USER_ID).header("X-User-Role", "USER"))
            .andExpect(status().isForbidden());
        mockMvc
            .perform(get("/api/v1/admin/exams/test").header("X-User-Id", USER_ID).header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk());

        var internal
            = mockMvc
                .perform(get("/internal/v1/test").header("X-User-Role", "INVALID"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        // then
        assertThat(internal.getContentAsString()).isEqualTo("anonymous");
    }

    /**
     * 인증된 요청과 비동기 재진입에서 요청 전용 인증을 유지하고 세션은 만들지 않는지 확인한다.
     *
     * @throws Exception MockMvc 비동기 요청을 수행하지 못한 경우
     */
    @Test
    @DisplayName("비동기 재진입에서도 Gateway 인증을 유지하고 HTTP 세션을 만들지 않는다")
    void retainsRequestAuthenticationAcrossAsyncDispatch() throws Exception {
        // given: 사용자 인증 정보를 포함한 비동기 요청을 준비한다.
        // when: 요청을 시작하고 비동기 결과를 다시 MVC에 전달한다.
        var request
            = mockMvc
                .perform(get("/api/v1/test/async-me").header("X-User-Id", USER_ID).header("X-User-Role", "USER"))
                .andReturn();
        // then: 비동기 결과에는 요청 인증이 남고 요청 스레드에는 컨텍스트와 세션이 남지 않는다.
        assertThat(request.getRequest().isAsyncStarted()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat((GatewayUser)request.getAsyncResult())
            .isEqualTo(new GatewayUser(USER_ID, com.backtoback.common.web.security.user.UserRole.USER));

        MvcResult dispatched = mockMvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andReturn();
        assertThat(dispatched.getRequest().getSession(false)).isNull();
    }

    /**
     * 요청 ID의 응답 전파·MDC 정리와 Boot 주입 RestClient.Builder의 나가는 헤더를 확인한다.
     *
     * @throws Exception MockMvc 요청 또는 HTTP 인터셉터 호출에 실패한 경우
     */
    @Test
    @DisplayName("요청 추적 ID를 응답과 나가는 HTTP 호출에 연결하고 요청 후 정리한다")
    void propagatesCorrelationIdAndRestoresMdc() throws Exception {
        // given: 나가는 HTTP 요청에서 추적 헤더를 관찰할 MockRestServiceServer를 설정한다.
        var clientBuilder = restClientBuilder.clone();
        var server = MockRestServiceServer.bindTo(clientBuilder).build();
        server
            .expect(
                request -> assertThat(request.getHeaders().getFirst(CorrelationIdFilter.HEADER))
                    .isEqualTo(CORRELATION_ID)
            )
            .andRespond(withSuccess());
        probeController.clientBuilder = clientBuilder;

        // when
        var response
            = mockMvc
                .perform(
                    get("/api/v1/test/correlation")
                        .header("X-User-Id", USER_ID)
                        .header("X-User-Role", "USER")
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        // then
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(CORRELATION_ID);
        assertThat(response.getContentAsString()).isEqualTo(CORRELATION_ID);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        server.verify();
    }

    /**
     * 실제 공통 예외·요청 추적 구성과 test controller를 등록한다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(ProbeController.class)
    static class MvcTestConfiguration {

    }

    /**
     * HTTP 연결 없이 contest 경로의 MVC 검증·오류·인증·추적 동작을 확인하는 테스트 컨트롤러다.
     */
    @RestController
    static class ProbeController {
        private RestClient.Builder clientBuilder;

        /**
         * 공개 대회 목록 경로의 테스트용 처리 결과를 반환한다.
         *
         * @return 공개 경로가 허용됐음을 나타내는 응답
         */
        @GetMapping("/api/v1/contests")
        String publicContests() {
            return "public-contests";
        }

        /**
         * 빈 요청 본문 필드 검증을 발생시킨다.
         *
         * @param request 이름 필드를 검증할 테스트 요청
         */
        @PostMapping("/api/v1/test/validation")
        void validation(@Valid @RequestBody ValidationRequest request) {}

        /**
         * 공통 비즈니스 예외 처리 결과를 재현한다.
         */
        @GetMapping("/api/v1/test/business-error")
        void businessError() {
            throw new ProbeBusinessException();
        }

        /**
         * 공통 처리기의 예상 밖 서버 오류 응답을 재현한다.
         */
        @GetMapping("/api/v1/test/server-error")
        void serverError() {
            throw new IllegalStateException("private server detail");
        }

        /**
         * 인증된 요청의 principal을 반환한다.
         *
         * @param user Gateway 헤더에서 생성한 사용자
         * @return 사용자 ID 또는 인증 정보가 없는 상태
         */
        @GetMapping("/api/v1/test/me")
        String me(@AuthenticationPrincipal GatewayUser user) {
            return user.userId();
        }

        /**
         * 관리자 권한 요청이 허용되는지 확인할 응답을 반환한다.
         *
         * @param user 관리자 Gateway principal
         * @return 권한이 허용됐음을 나타내는 응답
         */
        @GetMapping("/api/v1/admin/exams/test")
        String admin(@AuthenticationPrincipal GatewayUser user) {
            return user.role().name();
        }

        /**
         * 내부 경로에서 사용자 헤더를 인증으로 해석하지 않는지 확인한다.
         *
         * @param user 내부 요청에서 발견할 수 있는 principal
         * @return principal 부재 시 anonymous, 존재하면 사용자 ID
         */
        @GetMapping("/internal/v1/test")
        String internal(@AuthenticationPrincipal GatewayUser user) {
            return user == null ? "anonymous" : user.userId();
        }

        /**
         * MVC 비동기 요청 스레드에서 현재 요청의 principal을 읽는다.
         *
         * @return 비동기 재진입 중 보존된 Gateway 사용자
         */
        @GetMapping("/api/v1/test/async-me")
        Callable<GatewayUser> asyncMe() {
            return () -> (GatewayUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        }

        /**
         * 현재 요청의 추적 ID를 확인하고 동일 ID를 HTTP client로 전달한다.
         *
         * @return 현재 요청의 상관관계 ID
         */
        @GetMapping("/api/v1/test/correlation")
        String correlation() {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            clientBuilder.build().get().uri("http://localhost/test").retrieve().toBodilessEntity();
            return correlationId;
        }
    }

    /**
     * 테스트 입력의 필수 문자열 검증 결과를 재현한다.
     *
     * @param name 요청 본문에서 검증할 이름
     */
    record ValidationRequest(@NotBlank String name) {
    }

    /**
     * 공통 오류 응답의 상태·코드 전달을 검증하는 테스트 전용 비즈니스 예외다.
     */
    static class ProbeBusinessException extends BusinessException {
        /**
         * 충돌 오류를 공통 MVC 처리기에 전달한다.
         */
        ProbeBusinessException() {
            super(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
    }
}
