package com.backtoback.judge.submission.dto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.backtoback.judge.global.exception.custom.CodeTooLargeException;
import com.backtoback.judge.global.exception.custom.CommonInvalidRequestException;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 일반 풀이 제출 원본이다. 요청 해시는 코드의 공백·개행도 보존한다. (API 명세서 §3.1.2)
 *
 * @param problemId 문제 ID
 * @param problemRevisionId 고정 회차 ID
 * @param language 언어 코드
 * @param sourceCode 사용자 코드
 */
public record SubmissionCreateRequest(
    @NotBlank(message = "문제 ID는 필수입니다.")
    @Pattern(
        regexp = "[1-9][0-9]{0,18}",
        message = "문제 ID 형식이 잘못되었습니다."
    ) String problemId,
    @NotBlank(message = "회차 ID는 필수입니다.")
    @Pattern(
        regexp = "[1-9][0-9]{0,18}",
        message = "회차 ID 형식이 잘못되었습니다."
    ) String problemRevisionId,
    @NotBlank(message = "언어는 필수입니다.") String language,
    @NotBlank(message = "코드는 필수입니다.") String sourceCode
) {
    /**
     * HTTP 검증을 거치지 않는 호출에서도 식별자·원본·키의 필수 조건을 확인한다.
     *
     * @param key 멱등 키
     * @throws CommonInvalidRequestException 식별자·키·필수 값의 형식이 유효하지 않은 경우
     * @throws CodeTooLargeException 코드가 UTF-8 64KiB를 초과한 경우
     */
    public void validate(String key) {
        parseId(problemId);
        parseId(problemRevisionId);
        if (key == null || key.isBlank() || !key.matches("[\\x00-\\x7F]{1,100}") || language == null
            || language.isBlank() || sourceCode == null || sourceCode.isBlank()) {
            throw new CommonInvalidRequestException();
        }
        if (sourceCode.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new CodeTooLargeException();
        }
    }

    /**
     * 명세의 양수 64비트 식별자를 파싱한다.
     *
     * @param value JSON 식별자
     * @return 양수 ID
     * @throws CommonInvalidRequestException 식별자 형식이나 범위가 유효하지 않은 경우
     */
    public static long parseId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new CommonInvalidRequestException();
        }
    }

    /**
     * 필드 경계와 원본 코드 바이트를 보존하는 SHA-256 요청 해시를 계산한다.
     *
     * @return 소문자 16진수 해시
     */
    public String requestHash() {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            for (String value : new String[] {
                problemId,
                problemRevisionId,
                language,
                sourceCode
            }) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                output.writeInt(encoded.length);
                output.write(encoded);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("요청 해시를 계산할 수 없습니다.", exception);
        }
    }
}
