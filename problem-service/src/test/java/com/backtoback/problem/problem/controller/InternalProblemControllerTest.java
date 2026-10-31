package com.backtoback.problem.problem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.problem.problem.config.InternalProblemStubConfiguration;
import com.backtoback.problem.problem.service.InternalProblemService;

/** API 명세서 2.6.1~2.6.5·2.6.8~2.6.10 내부 Stub의 계약을 검증한다. */
@WebMvcTest(controllers = InternalProblemController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("internal-stub")
@Import(
    {
        InternalProblemStubConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class InternalProblemControllerTest {
    private static final String PROBLEM_PATH = "/internal/v1/problems";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("채점 사전 검증은 문제와 고정 회차의 실행 프로필을 함께 반환한다")
    void judgingValidationReturnsFixedRevisionAndExecutionProfile() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/301/revisions/1001/judging/validation"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.problemId").value("301"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.scope").value("GENERAL"))
            .andExpect(jsonPath("$.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.totalCount").value(2))
            .andExpect(jsonPath("$.executionProfiles[0].language").value("java"));
    }

    @Test
    @DisplayName("채점 자료는 공개·숨김 테스트의 실제 개발용 파일 참조를 반환한다")
    void judgingDataReturnsAccessibleAssetReferences() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/301/revisions/1001/judging/data"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.cases.length()").value(2))
            .andExpect(jsonPath("$.cases[0].testAssetId").value("501"))
            .andExpect(jsonPath("$.cases[0].asset.objectKey").value("test-cases/501.json"))
            .andExpect(jsonPath("$.cases[0].asset.objectVersion").value(nullValue()))
            .andExpect(jsonPath("$.cases[1].isSample").value(false));
    }

    @Test
    @DisplayName("고정 회차 본문은 호출 문맥과 기본 코드 및 공개 예제를 반환한다")
    void contentReturnsFixedRevisionForSuppliedContext() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/301/revisions/1001/content").header("X-Access-Context", "CONTEST:8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.problemId").value("301"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.starterCodes[0].language").value("java"))
            .andExpect(jsonPath("$.samples[0].testAssetId").value("501"))
            .andExpect(jsonPath("$.solvedStatus").doesNotExist());
    }

    @Test
    @DisplayName("본문 문맥이 없거나 형식이 잘못되면 COMMON_INVALID_REQUEST를 반환한다")
    void contentRejectsMissingOrMalformedContext() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/301/revisions/1001/content"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));

        mvc
            .perform(get(PROBLEM_PATH + "/301/revisions/1001/content").header("X-Access-Context", "EXAM:abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("본문 이미지는 원본 형식과 캐시 차단 응답 헤더로 반환한다")
    void imageReturnsStaticDevelopmentAsset() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/301/revisions/1001/images/701").header("X-Access-Context", "CONTEST:8"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/svg+xml"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("1 + 2 = 3")))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .header()
                    .string("Cache-Control", "no-store")
            )
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .header()
                    .string("X-Content-Type-Options", "nosniff")
            );

        mvc
            .perform(
                get(PROBLEM_PATH + "/301/revisions/1001/images/not-a-number").header("X-Access-Context", "CONTEST:8")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("문제 요약은 공개·비공개·미존재 항목을 각각 식별한다")
    void summariesReturnAvailableUnavailableAndMissingItems() throws Exception {
        mvc
            .perform(
                get(PROBLEM_PATH + "/summaries")
                    .param("ids", "301", "302", "303", "999", "601")
                    .param("purpose", "CONTEST")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.items[0].problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.items[1].visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.items[2].problemRevisionId").value(nullValue()))
            .andExpect(jsonPath("$.items[3].isAvailable").value(false))
            .andExpect(jsonPath("$.items[4].isAvailable").value(true));
    }

    @Test
    @DisplayName("잘못된 요약 용도와 50개 초과 ID는 COMMON_INVALID_REQUEST로 거절한다")
    void summariesRejectInvalidPurposeAndExcessIds() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/summaries").param("ids", "301").param("purpose", "OTHER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));

        String[] tooManyIds = new String[51];
        java.util.Arrays.fill(tooManyIds, "301");
        mvc
            .perform(get(PROBLEM_PATH + "/summaries").param("ids", tooManyIds).param("purpose", "EXAM"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("관리자 검증 자료는 같은 회차의 코드와 공개·숨김 테스트를 반환한다")
    void validationDataReturnsFixedCodeAndCases() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/validations/2001/data").header("X-Correlation-Id", "stub-request-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationId").value("2001"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.testCases[0].testAssetId").value("501"))
            .andExpect(jsonPath("$.testCases[1].isSample").value(false));
    }

    @Test
    @DisplayName("관리자 검증 자료는 상관관계 ID가 없으면 COMMON_INVALID_REQUEST를 반환한다")
    void validationDataRequiresCorrelationId() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/validations/2001/data"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("대회 전용 문제 연결은 고정 연결 결과를 반복 반환한다")
    void contestProblemLinkReturnsStableResult() throws Exception {
        String request = """
            {"contestId":"8","problemRevisionId":"2001","rowVersion":7,"actorMemberId":"7"}
            """;

        mvc
            .perform(put(PROBLEM_PATH + "/601/contest").contentType("application/json").content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.problemId").value("601"))
            .andExpect(jsonPath("$.contestId").value("8"))
            .andExpect(jsonPath("$.problemRevisionId").value("2001"))
            .andExpect(jsonPath("$.linkedSourceVersion").value(8))
            .andExpect(jsonPath("$.policyActorId").value("7"));
    }

    @Test
    @DisplayName("대회 전용 문제 연결은 오래된 기준 버전과 다른 연결 값을 충돌로 반환한다")
    void contestProblemLinkReturnsDocumentedConflicts() throws Exception {
        mvc
            .perform(put(PROBLEM_PATH + "/601/contest").contentType("application/json").content("""
                {"contestId":"8","problemRevisionId":"2001","rowVersion":6,"actorMemberId":"7"}
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_VERSION_CONFLICT"));

        mvc
            .perform(put(PROBLEM_PATH + "/601/contest").contentType("application/json").content("""
                {"contestId":"9","problemRevisionId":"2001","rowVersion":7,"actorMemberId":"7"}
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("문제 상태 작업은 확정·대기·중단 스냅샷을 반환한다")
    void operationsReturnOriginalOperationStates() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/301/operations/problem-hide-301"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("COMMITTED"))
            .andExpect(jsonPath("$.sourceVersion").value("10"));

        mvc
            .perform(get(PROBLEM_PATH + "/302/operations/problem-hide-302"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("PENDING"));

        mvc
            .perform(get(PROBLEM_PATH + "/301/operations/problem-restore-301"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ABORTED"));
    }

    @Test
    @DisplayName("없는 문제·회차·검증·상태 작업은 명세의 404 오류 코드를 반환한다")
    void missingTargetsReturnDocumentedNotFoundCodes() throws Exception {
        mvc
            .perform(get(PROBLEM_PATH + "/999/revisions/1001/judging/validation"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_NOT_FOUND"));

        mvc
            .perform(get(PROBLEM_PATH + "/301/revisions/9999/judging/data"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_REVISION_NOT_FOUND"));

        mvc
            .perform(get(PROBLEM_PATH + "/validations/9999/data").header("X-Correlation-Id", "stub-request-2"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_VALIDATION_NOT_FOUND"));

        mvc
            .perform(get(PROBLEM_PATH + "/301/operations/missing-operation"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_NOT_FOUND"));
    }

    @Test
    @DisplayName("Stub 설정은 internal-stub 프로필에서만 컨트롤러와 서비스를 등록한다")
    void stubConfigurationIsOptIn() {
        new ApplicationContextRunner()
            .withUserConfiguration(InternalProblemStubConfiguration.class, InternalProblemController.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(InternalProblemController.class);
            });

        new ApplicationContextRunner()
            .withPropertyValues(
                "spring.profiles.active=internal-stub",
                "problem.internal-api.enabled=true",
                "problem.internal-api.implementation=stub",
                "problem.internal-stub.enabled=true"
            )
            .withUserConfiguration(InternalProblemStubConfiguration.class, InternalProblemController.class)
            .run(context -> {
                assertThat(context).hasSingleBean(InternalProblemController.class);
                assertThat(context).hasSingleBean(InternalProblemService.class);
            });

        new ApplicationContextRunner()
            .withPropertyValues(
                "spring.profiles.active=internal-stub",
                "problem.internal-api.enabled=false",
                "problem.internal-api.implementation=stub",
                "problem.internal-stub.enabled=true"
            )
            .withUserConfiguration(InternalProblemStubConfiguration.class, InternalProblemController.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(InternalProblemController.class);
                assertThat(context).doesNotHaveBean(InternalProblemService.class);
            });

        new ApplicationContextRunner()
            .withPropertyValues(
                "spring.profiles.active=internal-stub",
                "problem.internal-api.enabled=true",
                "problem.internal-api.implementation=real",
                "problem.internal-stub.enabled=true"
            )
            .withUserConfiguration(InternalProblemStubConfiguration.class, InternalProblemController.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(InternalProblemController.class);
                assertThat(context).doesNotHaveBean(InternalProblemService.class);
            });

        new ApplicationContextRunner()
            .withPropertyValues(
                "spring.profiles.active=internal-stub",
                "problem.internal-api.enabled=true",
                "problem.internal-api.implementation=stub",
                "problem.internal-stub.enabled=false"
            )
            .withUserConfiguration(InternalProblemStubConfiguration.class, InternalProblemController.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(InternalProblemController.class);
                assertThat(context).doesNotHaveBean(InternalProblemService.class);
            });

        new ApplicationContextRunner()
            .withPropertyValues(
                "spring.profiles.active=local",
                "problem.internal-api.enabled=true",
                "problem.internal-api.implementation=real",
                "problem.internal-stub.enabled=true"
            )
            .withBean(InternalProblemService.class, () -> org.mockito.Mockito.mock(InternalProblemService.class))
            .withUserConfiguration(InternalProblemStubConfiguration.class, InternalProblemController.class)
            .run(context -> {
                assertThat(context).hasSingleBean(InternalProblemController.class);
                assertThat(context).hasSingleBean(InternalProblemService.class);
            });

        new ApplicationContextRunner()
            .withPropertyValues("problem.internal-api.enabled=true")
            .withUserConfiguration(InternalProblemController.class)
            .run(context -> assertThat(context).doesNotHaveBean(InternalProblemController.class));
    }
}
