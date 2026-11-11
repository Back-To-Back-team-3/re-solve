package com.backtoback.study.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.backtoback.study.hint.interceptor.HintFeatureInterceptor;

import lombok.RequiredArgsConstructor;

/**
 * MVC 인터셉터 등록.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final HintFeatureInterceptor hintFeatureInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(hintFeatureInterceptor).addPathPatterns("/api/v1/hint-requests/**");
    }
}
