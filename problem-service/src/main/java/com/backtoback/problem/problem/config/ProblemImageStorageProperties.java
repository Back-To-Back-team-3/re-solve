package com.backtoback.problem.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 문제 이미지 저장에 사용하는 S3 버킷 설정. */
@ConfigurationProperties(prefix = "problem.image-storage")
public record ProblemImageStorageProperties(String bucket) {
}
