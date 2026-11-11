package com.backtoback.study.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.backtoback.study.hint.config.HintFeatureProperties;
import com.backtoback.study.hint.interceptor.HintFeatureInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * MVC 인터셉터 등록.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final HintFeatureProperties hintFeatureProperties;
    private final ObjectMapper objectMapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
            .addInterceptor(new HintFeatureInterceptor(hintFeatureProperties, objectMapper))
            .addPathPatterns("/api/v1/hint-requests/**");
    }
}
