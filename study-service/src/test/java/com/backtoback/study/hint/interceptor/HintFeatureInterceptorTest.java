package com.backtoback.study.hint.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.study.hint.config.HintFeatureProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class HintFeatureInterceptorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("토글이 활성화(enabled=true)이면 preHandle이 true를 반환한다")
    void preHandle_enabled_returnsTrue() throws Exception {
        HintFeatureInterceptor interceptor = new HintFeatureInterceptor(new HintFeatureProperties(true), objectMapper);

        boolean result
            = interceptor.preHandle(mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object());

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("토글이 비활성화(enabled=false)이면 503 응답을 작성하고 false를 반환한다")
    void preHandle_disabled_writes503AndReturnsFalse() throws Exception {
        HintFeatureInterceptor interceptor = new HintFeatureInterceptor(new HintFeatureProperties(false), objectMapper);

        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        given(response.getWriter()).willReturn(new PrintWriter(sw));

        boolean result = interceptor.preHandle(mock(HttpServletRequest.class), response, new Object());

        assertThat(result).isFalse();
        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(sw.toString()).contains("HINT_FEATURE_DISABLED");
    }
}
