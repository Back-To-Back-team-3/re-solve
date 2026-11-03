package com.backtoback.integration.github.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GithubCommitPathResolverTest {

    private GithubCommitPathResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new GithubCommitPathResolver();
    }

    @Test
    @DisplayName("언어별 표준 파일명을 올바르게 결정한다")
    void resolveFileNameByLanguage() {
        assertThat(resolver.resolveFileName("java")).isEqualTo("Solution.java");
        assertThat(resolver.resolveFileName("python3")).isEqualTo("solution.py");
        assertThat(resolver.resolveFileName("python")).isEqualTo("solution.py");
        assertThat(resolver.resolveFileName("cpp")).isEqualTo("solution.cpp");
        assertThat(resolver.resolveFileName("c++")).isEqualTo("solution.cpp");
        assertThat(resolver.resolveFileName("c")).isEqualTo("main.c");
        assertThat(resolver.resolveFileName("javascript")).isEqualTo("solution.js");
        assertThat(resolver.resolveFileName("kotlin")).isEqualTo("Solution.kt");
        assertThat(resolver.resolveFileName("rust")).isEqualTo("solution.rust");
    }

    @Test
    @DisplayName("기본 템플릿에 난이도, 문제번호, 문제명, 언어별 파일명이 정상 치환된다")
    void resolvePathWithDefaultTemplate() {
        String template = "{난이도}/{문제번호}-{문제명}/{언어별 파일}";
        String path = resolver.resolvePath(template, 1, 101L, "두 수의 합", "python3");

        assertThat(path).isEqualTo("1/101-두-수의-합/solution.py");
    }

    @Test
    @DisplayName("문제명에 특수문자나 슬래시가 포함되어 있어도 안전하게 정제된다")
    void resolvePathWithSpecialCharacters() {
        String template = "{난이도}/{문제번호}-{문제명}/{언어별 파일}";
        String path = resolver.resolvePath(template, 2, 202L, "괄호 검증: (올바른 짝/순서?)", "java");

        assertThat(path).isEqualTo("2/202-괄호-검증-올바른-짝순서/Solution.java");
    }

    @Test
    @DisplayName("템플릿이 비어있으면 기본 규칙을 적용한다")
    void resolvePathWithNullOrEmptyTemplate() {
        String path = resolver.resolvePath(null, 3, 303L, "스터디룸 예약", "cpp");

        assertThat(path).isEqualTo("3/303-스터디룸-예약/solution.cpp");
    }
}
