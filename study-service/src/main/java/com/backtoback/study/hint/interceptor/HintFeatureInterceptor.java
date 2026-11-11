package com.backtoback.study.hint.interceptor;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import com.backtoback.common.api.ApiErrorResponse;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.hint.config.HintFeatureProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 힌트 기능 토글 인터셉터.
 * {@code study.hint.feature.enabled=false}이면 모든 힌트 API에 {@code HINT_FEATURE_DISABLED(503)}을 반환한다.
 */
@RequiredArgsConstructor
public class HintFeatureInterceptor implements HandlerInterceptor {

    private final HintFeatureProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {
        if (properties.enabled()) {
            return true;
        }

        ApiErrorResponse errorResponse = ApiErrorResponse.of(StudyErrorCode.HINT_FEATURE_DISABLED, List.of());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), errorResponse);

        return false;
    }
}
