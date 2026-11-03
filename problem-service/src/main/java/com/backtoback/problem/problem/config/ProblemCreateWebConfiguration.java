package com.backtoback.problem.problem.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.backtoback.problem.problem.service.ProblemCreateBodyReader;

import lombok.RequiredArgsConstructor;

/** 최초 등록 요청에만 본문 크기 제한과 엄격한 JSON 변환을 적용한다. */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class ProblemCreateWebConfiguration implements WebMvcConfigurer {
    private final ProblemCreateBodyReader reader;

    /** {@inheritDoc} */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConvertersList(converters -> converters.addFirst(reader));
    }
}
